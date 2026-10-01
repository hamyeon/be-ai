package com.vintic.backend.concurrency;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.autobid.domain.AutoBidSetting;
import com.vintic.backend.autobid.dto.AutoBidUpdateResponse;
import com.vintic.backend.autobid.repository.AutoBidSettingRepository;
import com.vintic.backend.common.dto.ApiResponse;
import com.vintic.backend.concurrency.support.RaceWindowDelay;
import com.vintic.backend.product.domain.Product;
import com.vintic.backend.product.repository.ProductRepository;
import com.vintic.backend.user.domain.User;
import com.vintic.backend.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

// #46 follow-up: AutoBidCommandService.updateAutoBid()가 own-setting을 non-locking SELECT로
// 읽던 시절엔, 같은 사용자가 서로 다른 Idempotency-Key로 동시에 cap을 올리면 REPEATABLE READ
// read view 때문에 나중에 커밋되는 쪽이 이미 반영된 더 높은 cap을 stale 값으로 덮어써
// "cap은 감소하지 않는다(§7, 40907 정책)" invariant가 깨졌다(실제 MySQL로 재현했었다).
//
// 지금은 own-setting 조회가 findCurrentByAuctionIdAndUserIdForUpdate(PESSIMISTIC_WRITE)로
// 바뀌었고, Auction FOR UPDATE를 먼저 획득하도록 순서도 바꿨다 - Auction 락이 두 트랜잭션을
// 완전히 직렬화하므로, 두 번째로 락을 얻는 쪽은 항상 첫 번째의 커밋 이후 상태를 locking read로
// 정확히 본다. 이 클래스는 그 수정이 실제 MySQL(Testcontainers, InnoDB)에서 성립하는지
// 검증하는 회귀다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@Testcontainers
class AutoBidCapUpdateConcurrencyMySqlIT {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    // Auction 락이 두 트랜잭션을 이미 완전히 직렬화하므로 이 delay가 없어도 최종 invariant는
    // 성립한다 - 다만 delay 없이는 두 HTTP 요청이 네트워크/스레드 디스패치 지연으로 사실상
    // 순차 실행돼(#46 follow-up 최초 시도에서 실제로 관찰됨) 이 테스트가 own-setting locking
    // read 자체를 제대로 행사하지 못한 채 "우연히" 통과할 수 있다. ManualBidConcurrencyRaceIT와
    // 동일한 방식으로 findCurrentByAuctionIdAndUserIdForUpdate() 반환 직후에 지연을 강제해,
    // 두 트랜잭션이 실제로 서로 다른 시점에 이 locking read를 통과하도록(= 락 경쟁이 실제로
    // 발생하도록) 만든다 - production 메서드가 나중에 다시 non-locking 조회로 되돌아가면 이
    // delay가 걸리지 않게 되어 회귀를 잡아내는 trap 역할도 한다.
    @TestConfiguration
    static class DelayConfig {

        @Bean
        RaceWindowDelay raceWindowDelay() {
            return new RaceWindowDelay();
        }

        @Bean
        @Primary
        AutoBidSettingRepository delayingAutoBidSettingRepository(
                AutoBidSettingRepository jpaAutoBidSettingRepository, RaceWindowDelay raceWindowDelay
        ) {
            AutoBidSettingRepository proxy = Mockito.mock(
                    AutoBidSettingRepository.class, AdditionalAnswers.delegatesTo(jpaAutoBidSettingRepository)
            );
            Mockito.doAnswer(invocation -> {
                Long auctionId = invocation.getArgument(0);
                Long userId = invocation.getArgument(1);
                Optional<AutoBidSetting> result = jpaAutoBidSettingRepository
                        .findCurrentByAuctionIdAndUserIdForUpdate(auctionId, userId);
                raceWindowDelay.applyIfTarget(auctionId);
                return result;
            }).when(proxy).findCurrentByAuctionIdAndUserIdForUpdate(Mockito.anyLong(), Mockito.anyLong());
            return proxy;
        }
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private AuctionRepository auctionRepository;

    @Autowired
    private AutoBidSettingRepository autoBidSettingRepository;

    @Autowired
    private RaceWindowDelay raceWindowDelay;

    private Auction persistLiveAuction() {
        User seller = userRepository.save(User.register(
                "seller-" + System.identityHashCode(new Object()) + "@vintic.local", "seller", null
        ));
        Product product = productRepository.save(new Product(
                seller,
                List.of("https://example.com/a.jpg"),
                "Nike", "Dunk Low", "Panda", 270, "B", "PARTIAL",
                300000, 350000, "285,000원 ~ 315,000원", 290000, "사유", "설명"
        ));
        // minNextBidAmount(110000)보다 낮은 100000을 초기 cap으로 둬서, 이 테스트가 검증하려는
        // "cap 하향 금지" 위반과 40906(CAP_TOO_LOW)이 서로 섞이지 않게 한다 - 두 경쟁 PATCH
        // (150000/200000) 모두 110000보다 커서 40906에는 걸리지 않는다.
        Auction auction = Auction.schedule(
                product, 105000L, 5000L, LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(1)
        );
        auction.start();
        return auctionRepository.save(auction);
    }

    private HttpEntity<Map<String, Object>> patchRequest(Long userId, String idempotencyKey, Long maxAmount) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-User-Id", String.valueOf(userId));
        headers.set("Idempotency-Key", idempotencyKey);
        return new HttpEntity<>(Map.of("maxAmount", maxAmount), headers);
    }

    @Test
    void 같은_사용자의_동시_cap_상향_PATCH는_더_높은_cap으로_수렴한다() throws Exception {
        Auction auction = persistLiveAuction();
        User bidder = userRepository.findById(1L).orElseThrow();

        AutoBidSetting existing = AutoBidSetting.reserve(auction, bidder, 100000L);
        existing.activate(); // ACTIVE여야 "상향만 허용(40907)" 정책이 적용된다(§7).
        autoBidSettingRepository.saveAndFlush(existing);

        String updateUrl = "/api/auctions/" + auction.getId() + "/auto-bids/me";

        raceWindowDelay.configure(auction.getId(), 1000);
        raceWindowDelay.arm();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<ResponseEntity<ApiResponse<AutoBidUpdateResponse>>> higherTask = () -> {
            ready.countDown();
            start.await();
            return restTemplate.exchange(
                    updateUrl, HttpMethod.PATCH, patchRequest(1L, "cap-update-higher-200000", 200000L),
                    new ParameterizedTypeReference<>() {
                    }
            );
        };
        Callable<ResponseEntity<ApiResponse<AutoBidUpdateResponse>>> lowerTask = () -> {
            ready.countDown();
            start.await();
            return restTemplate.exchange(
                    updateUrl, HttpMethod.PATCH, patchRequest(1L, "cap-update-higher-150000", 150000L),
                    new ParameterizedTypeReference<>() {
                    }
            );
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ResponseEntity<ApiResponse<AutoBidUpdateResponse>>> higherFuture = executor.submit(higherTask);
            Future<ResponseEntity<ApiResponse<AutoBidUpdateResponse>>> lowerFuture = executor.submit(lowerTask);
            ready.await();
            start.countDown();

            ResponseEntity<ApiResponse<AutoBidUpdateResponse>> higherResponse = higherFuture.get(30, TimeUnit.SECONDS);
            ResponseEntity<ApiResponse<AutoBidUpdateResponse>> lowerResponse = lowerFuture.get(30, TimeUnit.SECONDS);

            raceWindowDelay.disarm();

            // 실행 순서(어느 쪽이 Auction 락을 먼저 얻는지)에 따라 결과가 갈리지만, 허용되는
            // 조합은 정확히 둘뿐이다:
            //   - 150000이 먼저 커밋 → 200000은 그 fresh 150000 기준으로도 여전히 상향이라
            //     둘 다 200 OK (higher=200, lower=200)
            //   - 200000이 먼저 커밋 → 150000은 fresh 200000 기준 상향이 아니라 40907로 거부
            //     (higher=200, lower=409)
            // higher(200000)는 초기값(100000)이든 fresh 150000이든 항상 유효한 상향이므로
            // 어느 순서에서도 거부돼선 안 된다 - higher=409나 어느 쪽이든 500은 invariant 위반이다.
            assertThat(higherResponse.getStatusCode())
                    .as("cap=200000 요청은 실행 순서와 무관하게 항상 성공(200)해야 한다")
                    .isEqualTo(HttpStatus.OK);
            assertThat(lowerResponse.getStatusCode())
                    .as("cap=150000 요청은 먼저 커밋되면 200, 나중에 fresh 200000을 보면 409(40907)만 가능하다")
                    .isIn(HttpStatus.OK, HttpStatus.CONFLICT);

            AutoBidSetting reloaded = autoBidSettingRepository
                    .findByAuctionIdAndUserIdAndActiveSlotTrue(auction.getId(), 1L)
                    .orElseThrow();

            // 정책: cap은 감소하지 않는다 - 위 두 조합 중 어느 쪽이 실제로 발생하든 최종 cap은
            // 정확히 두 요청 중 더 높은 값(200000)으로 수렴해야 한다.
            assertThat(reloaded.getMaxAmount())
                    .as("동시 PATCH 이후 최종 cap은 정확히 200000이어야 한다")
                    .isEqualTo(200000L);
        } finally {
            raceWindowDelay.disarm();
            executor.shutdown();
        }
    }
}
