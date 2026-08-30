package com.vintic.backend.concurrency;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.bid.dto.PlaceBidResponse;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

// #45: 실제 MySQL에서 Auction row PESSIMISTIC_WRITE를 한 트랜잭션이 보유한 상태로 다른 사용자
// command를 실행해 lock 대기 타임아웃을 재현하고, 그 경로가 500이 아니라 409/40909로 응답하는지
// 검증한다. --innodb-lock-wait-timeout=2로 MySQL 서버 자체의 대기 시간을 짧게 줘서 테스트를
// 빠르고 결정적으로 만든다(production 코드/설정은 건드리지 않는다 - 이 컨테이너에만 적용).
//
// 락 보유는 production 코드를 감싸거나 spy하지 않고, 테스트가 직접 TransactionTemplate으로
// findByIdForUpdate()를 호출해 만든다 - AuctionRepository.findByIdForUpdate()가 실제로
// PESSIMISTIC_WRITE를 요청하는 그 코드 경로 자체를 그대로 쓴다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@Testcontainers
class AuctionLockTimeoutMySqlIT {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withCommand("--innodb-lock-wait-timeout=2");

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
    private PlatformTransactionManager transactionManager;

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
                product, 10000L, 5000L, LocalDateTime.now().plusHours(1), LocalDateTime.now().plusHours(2)
        );
        auction.start();
        return auctionRepository.save(auction);
    }

    @Test
    void 다른_트랜잭션이_Auction_row_lock을_보유_중이면_409와_40909를_반환한다() throws Exception {
        Auction auction = persistLiveAuction();
        String url = "/api/auctions/" + auction.getId() + "/bids";

        CountDownLatch lockAcquired = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

        ExecutorService executor = Executors.newFixedThreadPool(1);
        try {
            Future<?> holder = executor.submit(() -> transactionTemplate.execute(status -> {
                auctionRepository.findByIdForUpdate(auction.getId()); // PESSIMISTIC_WRITE 획득
                lockAcquired.countDown();
                try {
                    // 이 트랜잭션이 커밋/롤백되기 전까지 lock을 계속 보유한다 - 아래 HTTP 요청이
                    // innodb-lock-wait-timeout(2초)을 실제로 소진하도록 충분히 오래 기다린다.
                    releaseLock.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }));

            assertThat(lockAcquired.await(10, TimeUnit.SECONDS)).isTrue();

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-User-Id", "2");
            headers.set("Idempotency-Key", "lock-timeout-check");
            ResponseEntity<ApiResponse<PlaceBidResponse>> response = restTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(Map.of("amount", 15000L), headers),
                    new ParameterizedTypeReference<>() {
                    }
            );

            releaseLock.countDown();
            holder.get(10, TimeUnit.SECONDS);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody().success()).isFalse();
            assertThat(response.getBody().error().code()).isEqualTo(40909);
        } finally {
            executor.shutdown();
        }
    }
}
