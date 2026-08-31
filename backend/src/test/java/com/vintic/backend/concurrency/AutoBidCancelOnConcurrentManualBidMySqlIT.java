package com.vintic.backend.concurrency;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.autobid.dto.AutoBidRegisterResponse;
import com.vintic.backend.autobid.repository.AutoBidSettingRepository;
import com.vintic.backend.bid.dto.PlaceBidResponse;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

// #46 follow-up: PLACE_BID의 cancelOwnActiveAutoBidIfPresent()도 UPDATE_AUTO_BID와 같은
// 메커니즘에 노출돼 있었다 - own-setting 조회가 Auction FOR UPDATE "이후"에 실행되더라도,
// non-locking SELECT라면 그보다 앞서 실행된 idempotency claim 조회가 고정한 REPEATABLE READ
// read view를 그대로 쓴다. 즉 같은 사용자가 (a) 다른 요청으로 방금 AutoBid를 CREATE(커밋)하고
// (b) 거의 동시에 Manual Bid를 제출하면, Manual Bid 트랜잭션의 read view가 (a)의 커밋보다
// 먼저 고정된 경우 "기존 설정 없음"으로 stale하게 읽어 autoBidCanceled=false를 응답하고 실제로는
// 취소돼야 할 ACTIVE AutoBidSetting을 취소하지 않을 수 있었다(§9 정책 위반).
//
// 지금은 이 조회도 findCurrentByAuctionIdAndUserIdForUpdate(PESSIMISTIC_WRITE)를 쓴다 - Auction
// 락을 이미 잡은 뒤이므로, CREATE가 Manual Bid보다 먼저 Auction 락을 얻고 커밋을 마치면 Manual
// Bid는 자신의 own-setting locking read에서 그 커밋을 항상 최신으로 본다. 이 테스트는 CREATE가
// 확실히 먼저 Auction 락을 잡고 그 락을 보유한 채 지연되도록 강제해(ManualBidConcurrencyRaceIT와
// 동일한 AuctionRepository delay 패턴), Manual Bid가 그 이후에야 own-setting을 확인하는
// 상황을 재현하고, 고쳐진 코드가 그 상황에서도 정확히 취소하는지 검증한다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@Testcontainers
class AutoBidCancelOnConcurrentManualBidMySqlIT {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    // production AuctionRepository를 감싸 findByIdForUpdate() 결과를 실제로 받은 "직후"에만
    // 지연시키는 test-only 대리 빈이다(ManualBidConcurrencyRaceIT와 동일한 패턴). CREATE
    // 요청이 먼저 Auction 락을 잡고 이 지연 동안 락을 보유하게 만들어, Manual Bid 요청이 그
    // 뒤에야 자신의 Auction 락과 own-setting locking read를 통과하도록 강제한다.
    @TestConfiguration
    static class DelayConfig {

        @Bean
        RaceWindowDelay raceWindowDelay() {
            return new RaceWindowDelay();
        }

        @Bean
        @Primary
        AuctionRepository delayingAuctionRepository(
                AuctionRepository jpaAuctionRepository, RaceWindowDelay raceWindowDelay
        ) {
            AuctionRepository proxy = Mockito.mock(
                    AuctionRepository.class, AdditionalAnswers.delegatesTo(jpaAuctionRepository)
            );
            Mockito.doAnswer(invocation -> {
                Long id = invocation.getArgument(0);
                Optional<Auction> result = jpaAuctionRepository.findByIdForUpdate(id);
                raceWindowDelay.applyIfTarget(id);
                return result;
            }).when(proxy).findByIdForUpdate(Mockito.anyLong());
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
        Auction auction = Auction.schedule(
                product, 105000L, 5000L, LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(1)
        );
        auction.start();
        return auctionRepository.save(auction);
    }

    private HttpEntity<Map<String, Object>> createAutoBidRequest(Long userId, String idempotencyKey, Long maxAmount) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-User-Id", String.valueOf(userId));
        headers.set("Idempotency-Key", idempotencyKey);
        return new HttpEntity<>(Map.of("maxAmount", maxAmount), headers);
    }

    private HttpEntity<Map<String, Object>> bidRequest(Long userId, String idempotencyKey, Long amount) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-User-Id", String.valueOf(userId));
        headers.set("Idempotency-Key", idempotencyKey);
        return new HttpEntity<>(Map.of("amount", amount), headers);
    }

    @Test
    void 직전에_커밋된_CREATE의_own_setting을_동시_Manual_Bid가_최신으로_보고_취소한다() throws Exception {
        Auction auction = persistLiveAuction(); // currentPrice=105000, minNextBidAmount=110000
        Long userId = 1L;

        String createUrl = "/api/auctions/" + auction.getId() + "/auto-bids";
        String bidsUrl = "/api/auctions/" + auction.getId() + "/bids";

        raceWindowDelay.configure(auction.getId(), 1000);
        raceWindowDelay.arm();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // CREATE를 먼저 제출한다 - Auction 락을 먼저 잡고 delay(1000ms) 동안 보유한다.
            Future<ResponseEntity<ApiResponse<AutoBidRegisterResponse>>> createFuture = executor.submit(() ->
                    restTemplate.exchange(
                            createUrl, HttpMethod.POST, createAutoBidRequest(userId, "cancel-race-create", 200000L),
                            new ParameterizedTypeReference<>() {
                            }
                    )
            );
            // CREATE가 Auction 락을 얻고 delay에 진입할 시간을 준다 - 그 뒤에 Manual Bid를 보내면
            // Manual Bid의 idempotency claim 조회(= read view 확립)는 CREATE의 커밋보다 먼저
            // 일어나지만, Manual Bid의 own-setting 조회는 CREATE의 커밋 이후(Auction 락 대기가
            // 풀린 뒤)에야 실행된다 - 고쳐지기 전 코드라면 stale read가 발생했을 지점이다.
            Thread.sleep(300);
            Future<ResponseEntity<ApiResponse<PlaceBidResponse>>> bidFuture = executor.submit(() ->
                    restTemplate.exchange(
                            bidsUrl, HttpMethod.POST, bidRequest(userId, "cancel-race-bid", 110000L),
                            new ParameterizedTypeReference<>() {
                            }
                    )
            );

            ResponseEntity<ApiResponse<AutoBidRegisterResponse>> createResponse = createFuture.get(30, TimeUnit.SECONDS);
            ResponseEntity<ApiResponse<PlaceBidResponse>> bidResponse = bidFuture.get(30, TimeUnit.SECONDS);

            raceWindowDelay.disarm();

            assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(bidResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

            // 핵심 assertion: CREATE로 커밋된 ACTIVE AutoBidSetting을 Manual Bid가 최신으로
            // 보고 취소했어야 한다.
            assertThat(bidResponse.getBody().data().autoBidCanceled())
                    .as("동시 CREATE 직후 Manual Bid는 최신 own setting을 보고 취소할 수 있어야 한다")
                    .isTrue();
            assertThat(autoBidSettingRepository.findByAuctionIdAndUserIdAndActiveSlotTrue(auction.getId(), userId))
                    .as("취소된 설정은 activeSlot=null이라 더 이상 현재 설정으로 조회되지 않는다")
                    .isEmpty();
        } finally {
            raceWindowDelay.disarm();
            executor.shutdown();
        }
    }
}
