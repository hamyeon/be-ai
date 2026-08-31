package com.vintic.backend.concurrency;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.backupoffer.domain.BackupOffer;
import com.vintic.backend.backupoffer.dto.BackupOfferAcceptResponse;
import com.vintic.backend.backupoffer.repository.BackupOfferRepository;
import com.vintic.backend.bid.domain.Bid;
import com.vintic.backend.bid.domain.BidType;
import com.vintic.backend.bid.repository.BidRepository;
import com.vintic.backend.common.dto.ApiResponse;
import com.vintic.backend.order.repository.OrderRepository;
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
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

// #56-3: BackupOffer accept의 동시성 - (a) 서로 다른 Idempotency-Key로 같은 offer를 동시에
// accept해도 Order는 1건만 생성되는지(BackupOfferRepository.findByIdForUpdate가 두 요청을
// 직렬화하고, 나중에 실행되는 쪽은 이미 ACCEPTED가 된 offer를 보고 40912로 거절된다),
// (b) 같은 key로 재요청하면 커맨드를 다시 실행하지 않고 최초 응답을 그대로 replay하는지를
// AuctionLikeConcurrencyMySqlIT/AutoBidConcurrencyMySqlIT와 동일한 harness로 검증한다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@Testcontainers
class BackupOfferAcceptConcurrencyMySqlIT {

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
    private BidRepository bidRepository;

    @Autowired
    private BackupOfferRepository backupOfferRepository;

    @Autowired
    private OrderRepository orderRepository;

    private BackupOffer persistWaitingOffer(User candidate) {
        User seller = userRepository.save(User.register("seller-" + System.nanoTime() + "@vintic.local", "seller", null));
        User winner = userRepository.save(User.register("winner-" + System.nanoTime() + "@vintic.local", "winner", null));
        Product product = productRepository.save(new Product(
                seller,
                List.of("https://example.com/a.jpg"),
                "Nike", "Dunk Low", "Panda", 270, "B", "PARTIAL",
                300000, 350000, "285,000원 ~ 315,000원", 290000, "사유", "설명"
        ));
        Auction auction = Auction.schedule(
                product, 10000L, 5000L, LocalDateTime.now().minusHours(2), LocalDateTime.now().minusHours(1)
        );
        auction.start();
        Auction savedAuction = auctionRepository.save(auction);
        bidRepository.save(Bid.place(savedAuction, candidate, 15000L, BidType.MANUAL));
        savedAuction.placeManualBid(candidate, 15000L);
        bidRepository.save(Bid.place(savedAuction, winner, 20000L, BidType.MANUAL));
        savedAuction.placeManualBid(winner, 20000L);
        savedAuction.end();
        return backupOfferRepository.save(BackupOffer.create(savedAuction, candidate, 15000L));
    }

    private HttpEntity<Void> acceptRequest(Long userId, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", String.valueOf(userId));
        headers.set("Idempotency-Key", idempotencyKey);
        return new HttpEntity<>(headers);
    }

    @Test
    void 서로_다른_key로_동시_accept해도_Order는_1건만_생성된다() throws Exception {
        User candidate = userRepository.save(User.register("candidate-" + System.nanoTime() + "@vintic.local", "candidate", null));
        BackupOffer offer = persistWaitingOffer(candidate);
        String url = "/api/backup-offers/" + offer.getId() + "/accept";

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<ResponseEntity<ApiResponse<BackupOfferAcceptResponse>>> taskA = () -> {
            ready.countDown();
            start.await();
            return restTemplate.exchange(
                    url, HttpMethod.POST, acceptRequest(candidate.getId(), "key-a"),
                    new ParameterizedTypeReference<>() {
                    }
            );
        };
        Callable<ResponseEntity<ApiResponse<BackupOfferAcceptResponse>>> taskB = () -> {
            ready.countDown();
            start.await();
            return restTemplate.exchange(
                    url, HttpMethod.POST, acceptRequest(candidate.getId(), "key-b"),
                    new ParameterizedTypeReference<>() {
                    }
            );
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ResponseEntity<ApiResponse<BackupOfferAcceptResponse>>> futureA = executor.submit(taskA);
            Future<ResponseEntity<ApiResponse<BackupOfferAcceptResponse>>> futureB = executor.submit(taskB);
            ready.await();
            start.countDown();

            ResponseEntity<ApiResponse<BackupOfferAcceptResponse>> responseA = futureA.get(30, TimeUnit.SECONDS);
            ResponseEntity<ApiResponse<BackupOfferAcceptResponse>> responseB = futureB.get(30, TimeUnit.SECONDS);

            List<HttpStatus> statuses = List.of(
                    HttpStatus.valueOf(responseA.getStatusCode().value()),
                    HttpStatus.valueOf(responseB.getStatusCode().value())
            );
            assertThat(statuses).containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.CONFLICT);

            long orderCount = orderRepository.findAll().stream()
                    .filter(o -> o.getBuyer().getId().equals(candidate.getId()))
                    .count();
            assertThat(orderCount)
                    .as("uk_order_auction_buyer가 서로 다른 key의 동시 accept에서도 row 1개만 남기는지")
                    .isEqualTo(1);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void 같은_key로_재요청하면_최초_응답을_그대로_replay하고_Order를_다시_만들지_않는다() {
        User candidate = userRepository.save(User.register("candidate-" + System.nanoTime() + "@vintic.local", "candidate", null));
        BackupOffer offer = persistWaitingOffer(candidate);
        String url = "/api/backup-offers/" + offer.getId() + "/accept";

        ResponseEntity<ApiResponse<BackupOfferAcceptResponse>> first = restTemplate.exchange(
                url, HttpMethod.POST, acceptRequest(candidate.getId(), "replay-key"),
                new ParameterizedTypeReference<>() {
                }
        );
        ResponseEntity<ApiResponse<BackupOfferAcceptResponse>> second = restTemplate.exchange(
                url, HttpMethod.POST, acceptRequest(candidate.getId(), "replay-key"),
                new ParameterizedTypeReference<>() {
                }
        );

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getBody().data().orderId()).isEqualTo(first.getBody().data().orderId());
        assertThat(second.getBody().data().paymentDeadline()).isEqualTo(first.getBody().data().paymentDeadline());

        long orderCount = orderRepository.findAll().stream()
                .filter(o -> o.getBuyer().getId().equals(candidate.getId()))
                .count();
        assertThat(orderCount).isEqualTo(1);
    }
}
