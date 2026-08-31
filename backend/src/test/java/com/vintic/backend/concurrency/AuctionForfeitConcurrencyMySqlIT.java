package com.vintic.backend.concurrency;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.dto.AuctionForfeitResponse;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.backupoffer.repository.BackupOfferRepository;
import com.vintic.backend.bid.domain.Bid;
import com.vintic.backend.bid.domain.BidType;
import com.vintic.backend.bid.repository.BidRepository;
import com.vintic.backend.common.dto.ApiResponse;
import com.vintic.backend.order.domain.Order;
import com.vintic.backend.order.domain.OrderStatus;
import com.vintic.backend.order.repository.OrderRepository;
import com.vintic.backend.penalty.repository.PenaltyRepository;
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

// #56-2: 같은 낙찰자가 동시에 forfeit을 두 번 호출해도(더블클릭 등 - Idempotency-Key로 보호되지
// 않는다, §0.11에 이 endpoint가 없다) penalty/BackupOffer가 정확히 1건씩만 남아야 한다(사용자
// 확정 정책). AuctionLikeConcurrencyMySqlIT/AutoBidConcurrencyMySqlIT와 동일한 harness를
// 재사용한다. Auction FOR UPDATE -> Order FOR UPDATE 락 순서가 두 호출을 직렬화하고, 나중에
// 실행되는 쪽은 CANCELED로 바뀐 Order를 보고 state-idempotent 200으로 흡수한다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@Testcontainers
class AuctionForfeitConcurrencyMySqlIT {

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
    private OrderRepository orderRepository;

    @Autowired
    private PenaltyRepository penaltyRepository;

    @Autowired
    private BackupOfferRepository backupOfferRepository;

    @Autowired
    private BidRepository bidRepository;

    private Auction persistEndedAuctionWithWinnerAndCandidate(User winner, User candidate) {
        User seller = userRepository.save(User.register(
                "seller-" + System.nanoTime() + "@vintic.local", "seller", null
        ));
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
        bidRepository.save(Bid.place(auction, candidate, 20000L, BidType.MANUAL));
        auction.placeManualBid(candidate, 20000L);
        bidRepository.save(Bid.place(auction, winner, 30000L, BidType.MANUAL));
        auction.placeManualBid(winner, 30000L);
        auction.end();
        Auction saved = auctionRepository.save(auction);
        orderRepository.save(Order.createForWinner(saved, winner, 30000L, 3000L, saved.getEndAt().plusHours(24)));
        return saved;
    }

    private HttpEntity<Void> forfeitRequest(Long userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", String.valueOf(userId));
        return new HttpEntity<>(headers);
    }

    @Test
    void 같은_낙찰자가_동시에_forfeit을_두_번_호출해도_penalty와_BackupOffer는_1건씩만_남는다() throws Exception {
        User winner = userRepository.save(User.register("winner-" + System.nanoTime() + "@vintic.local", "winner", null));
        User candidate = userRepository.save(User.register("candidate-" + System.nanoTime() + "@vintic.local", "candidate", null));
        Auction auction = persistEndedAuctionWithWinnerAndCandidate(winner, candidate);
        String url = "/api/auctions/" + auction.getId() + "/award/forfeit";

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<ResponseEntity<ApiResponse<AuctionForfeitResponse>>> task = () -> {
            ready.countDown();
            start.await();
            return restTemplate.exchange(
                    url, HttpMethod.POST, forfeitRequest(winner.getId()),
                    new ParameterizedTypeReference<>() {
                    }
            );
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ResponseEntity<ApiResponse<AuctionForfeitResponse>>> future1 = executor.submit(task);
            Future<ResponseEntity<ApiResponse<AuctionForfeitResponse>>> future2 = executor.submit(task);
            ready.await();
            start.countDown();

            ResponseEntity<ApiResponse<AuctionForfeitResponse>> response1 = future1.get(30, TimeUnit.SECONDS);
            ResponseEntity<ApiResponse<AuctionForfeitResponse>> response2 = future2.get(30, TimeUnit.SECONDS);

            assertThat(response1.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response2.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response1.getBody().data().result().name()).isEqualTo("FORFEITED");
            assertThat(response2.getBody().data().result().name()).isEqualTo("FORFEITED");

            Order order = orderRepository.findByAuctionIdAndBuyerId(auction.getId(), winner.getId()).orElseThrow();
            assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELED);

            assertThat(penaltyRepository.count())
                    .as("uk_penalty_auction_user_type이 동시 forfeit에서도 row 1개만 남기는지")
                    .isEqualTo(1);
            assertThat(backupOfferRepository.count())
                    .as("uk_backup_offer_auction_candidate가 동시 forfeit에서도 row 1개만 남기는지")
                    .isEqualTo(1);
        } finally {
            executor.shutdown();
        }
    }
}
