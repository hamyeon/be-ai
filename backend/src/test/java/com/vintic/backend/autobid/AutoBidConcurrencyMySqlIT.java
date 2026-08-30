package com.vintic.backend.autobid;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.autobid.dto.AutoBidRegisterResponse;
import com.vintic.backend.autobid.repository.AutoBidSettingRepository;
import com.vintic.backend.common.dto.ApiResponse;
import com.vintic.backend.product.domain.Product;
import com.vintic.backend.product.repository.ProductRepository;
import com.vintic.backend.user.domain.User;
import com.vintic.backend.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
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
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

// #41 핵심 invariant 2번(같은 (user, auction)의 현재 AutoBidSetting은 최대 1개) 검증. 사전 조회로
// 걸러지는 순차 케이스는 AutoBidCommandServiceTest(H2)로 이미 충분하다 - 여기서는 두 요청이
// "동시에" 사전 조회를 통과한 뒤 실제 INSERT가 경쟁하는 race만 실제 MySQL(InnoDB, Testcontainers)로
// 검증한다. H2로는 이 race window를 신뢰성 있게 재현할 수 없다(ManualBidIdempotencyMySqlIT와 동일 이유).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@Testcontainers
class AutoBidConcurrencyMySqlIT {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
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

    private HttpEntity<Map<String, Object>> requestEntity(Long userId, String idempotencyKey, Long maxAmount) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-User-Id", String.valueOf(userId));
        headers.set("Idempotency-Key", idempotencyKey);
        return new HttpEntity<>(Map.of("maxAmount", maxAmount), headers);
    }

    @Test
    void 같은_user_같은_Auction에_서로_다른_key로_동시에_등록해도_현재_설정은_1개만_생성된다() throws Exception {
        Auction auction = persistLiveAuction();
        String url = "/api/auctions/" + auction.getId() + "/auto-bids";

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<ResponseEntity<ApiResponse<AutoBidRegisterResponse>>> taskA = () -> {
            ready.countDown();
            start.await();
            return restTemplate.exchange(
                    url, HttpMethod.POST,
                    requestEntity(2L, "concurrent-key-A", 200000L),
                    new ParameterizedTypeReference<>() {
                    }
            );
        };
        Callable<ResponseEntity<ApiResponse<AutoBidRegisterResponse>>> taskB = () -> {
            ready.countDown();
            start.await();
            return restTemplate.exchange(
                    url, HttpMethod.POST,
                    requestEntity(2L, "concurrent-key-B", 200000L),
                    new ParameterizedTypeReference<>() {
                    }
            );
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ResponseEntity<ApiResponse<AutoBidRegisterResponse>>> futureA = executor.submit(taskA);
            Future<ResponseEntity<ApiResponse<AutoBidRegisterResponse>>> futureB = executor.submit(taskB);
            ready.await();
            start.countDown();

            ResponseEntity<ApiResponse<AutoBidRegisterResponse>> responseA = futureA.get(30, TimeUnit.SECONDS);
            ResponseEntity<ApiResponse<AutoBidRegisterResponse>> responseB = futureB.get(30, TimeUnit.SECONDS);

            List<ResponseEntity<ApiResponse<AutoBidRegisterResponse>>> responses = List.of(responseA, responseB);
            long createdCount = responses.stream().filter(r -> r.getStatusCode() == HttpStatus.CREATED).count();
            long conflictCount = responses.stream().filter(r -> r.getStatusCode() == HttpStatus.CONFLICT).count();

            // 둘 다 500으로 새는 경우, 둘 다 201로 성공하는 경우를 모두 배제한다 - 정확히 하나만 성공해야 한다.
            assertThat(createdCount).isEqualTo(1);
            assertThat(conflictCount).isEqualTo(1);

            ResponseEntity<ApiResponse<AutoBidRegisterResponse>> conflictResponse = responseA.getStatusCode() == HttpStatus.CONFLICT
                    ? responseA : responseB;
            // DB unique 제약 위반이 raw SQL/JPA 예외(500)로 새지 않고 계약이 정의한 40908로 변환됐는지 확인한다.
            assertThat(conflictResponse.getBody().success()).isFalse();
            assertThat(conflictResponse.getBody().error().code()).isEqualTo(40908);

            // 같은 컨테이너/DB를 이 클래스의 다른 @Test와 공유하므로 findAll()은 이 테스트의
            // auction으로 범위를 좁혀야 한다 - 그렇지 않으면 다른 테스트가 만든 row까지 섞인다.
            long rowsForThisAuction = autoBidSettingRepository.findAll().stream()
                    .filter(setting -> setting.getAuction().getId().equals(auction.getId()))
                    .count();
            assertThat(rowsForThisAuction).isEqualTo(1);
            assertThat(autoBidSettingRepository.findByAuctionIdAndUserIdAndActiveSlotTrue(auction.getId(), 2L)).isPresent();
        } finally {
            executor.shutdown();
        }
    }

    // #44: PLACE_BID는 ManualBidIdempotencyMySqlIT로 이미 같은 key 동시 요청을 실제 MySQL로
    // 검증하지만, CREATE_AUTO_BID는 없었다 - 같은 (user, auction, key) 동시 요청이 idempotency
    // UNIQUE 경쟁에서 loser가 500이 아니라 winner와 동일한 결과로 replay되는지 확인한다.
    // 위 테스트(서로 다른 key)는 active-slot UNIQUE 레이어를, 이 테스트는 idempotency UNIQUE
    // 레이어를 검증한다는 점에서 서로 다른 invariant다.
    @Test
    void 같은_user_같은_Auction에_같은_key로_동시에_등록해도_row는_1건만_생성된다() throws Exception {
        Auction auction = persistLiveAuction();
        String url = "/api/auctions/" + auction.getId() + "/auto-bids";

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<ResponseEntity<ApiResponse<AutoBidRegisterResponse>>> task = () -> {
            ready.countDown();
            start.await();
            return restTemplate.exchange(
                    url, HttpMethod.POST,
                    requestEntity(1L, "same-key-concurrent", 200000L),
                    new ParameterizedTypeReference<>() {
                    }
            );
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ResponseEntity<ApiResponse<AutoBidRegisterResponse>>> future1 = executor.submit(task);
            Future<ResponseEntity<ApiResponse<AutoBidRegisterResponse>>> future2 = executor.submit(task);
            ready.await();
            start.countDown();

            ResponseEntity<ApiResponse<AutoBidRegisterResponse>> response1 = future1.get(30, TimeUnit.SECONDS);
            ResponseEntity<ApiResponse<AutoBidRegisterResponse>> response2 = future2.get(30, TimeUnit.SECONDS);

            assertThat(response1.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(response2.getStatusCode()).isEqualTo(HttpStatus.CREATED);

            Long settingId1 = response1.getBody().data().autoBidSettingId();
            Long settingId2 = response2.getBody().data().autoBidSettingId();
            assertThat(settingId1).isEqualTo(settingId2);

            long rowsForThisAuction = autoBidSettingRepository.findAll().stream()
                    .filter(setting -> setting.getAuction().getId().equals(auction.getId()))
                    .count();
            assertThat(rowsForThisAuction).isEqualTo(1);
            assertThat(autoBidSettingRepository.findByAuctionIdAndUserIdAndActiveSlotTrue(auction.getId(), 1L))
                    .hasValueSatisfying(setting -> assertThat(setting.getId()).isEqualTo(settingId1));
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void 취소_이후_재등록_요청은_실제_MySQL에서도_성공한다() {
        Auction auction = persistLiveAuction();
        String createUrl = "/api/auctions/" + auction.getId() + "/auto-bids";
        String cancelUrl = "/api/auctions/" + auction.getId() + "/auto-bids/me";

        ResponseEntity<ApiResponse<AutoBidRegisterResponse>> first = restTemplate.exchange(
                createUrl, HttpMethod.POST, requestEntity(3L, "first-key", 200000L),
                new ParameterizedTypeReference<>() {
                }
        );
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        HttpHeaders cancelHeaders = new HttpHeaders();
        cancelHeaders.set("X-User-Id", "3");
        restTemplate.exchange(cancelUrl, HttpMethod.DELETE, new HttpEntity<>(cancelHeaders), Void.class);

        ResponseEntity<ApiResponse<AutoBidRegisterResponse>> second = restTemplate.exchange(
                createUrl, HttpMethod.POST, requestEntity(3L, "second-key", 150000L),
                new ParameterizedTypeReference<>() {
                }
        );

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long rowsForThisAuction = autoBidSettingRepository.findAll().stream()
                .filter(setting -> setting.getAuction().getId().equals(auction.getId()))
                .count();
        assertThat(rowsForThisAuction).isEqualTo(2);
        assertThat(autoBidSettingRepository.findByAuctionIdAndUserIdAndActiveSlotTrue(auction.getId(), 3L))
                .hasValueSatisfying(setting -> assertThat(setting.getMaxAmount()).isEqualTo(150000L));
    }
}
