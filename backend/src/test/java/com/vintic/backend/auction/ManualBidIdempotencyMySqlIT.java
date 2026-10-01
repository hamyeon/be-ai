package com.vintic.backend.auction;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.bid.dto.PlaceBidResponse;
import com.vintic.backend.bid.repository.BidRepository;
import com.vintic.backend.bid.repository.IdempotencyRepository;
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

// #32 핵심 검증: 실제 MySQL(Testcontainers, InnoDB)에서 (1) 입찰 validation 실패 시
// Idempotency claim까지 같이 롤백되는지, (2) 동일 identity의 동시 요청에서 UNIQUE 경쟁 후
// loser가 500이 아니라 winner와 동일한 결과로 replay되는지를 실제 HTTP 왕복으로 확인한다.
// H2 @DataJpaTest로는 이 둘을 신뢰성 있게 검증할 수 없다 — 자세한 이유는
// ManualBidServiceTest의 관련 주석 참고.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@Testcontainers
class ManualBidIdempotencyMySqlIT {

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
    private IdempotencyRepository idempotencyRepository;

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

    private HttpEntity<Map<String, Object>> requestEntity(Long userId, String idempotencyKey, Long amount) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-User-Id", String.valueOf(userId));
        headers.set("Idempotency-Key", idempotencyKey);
        return new HttpEntity<>(Map.of("amount", amount), headers);
    }

    @Test
    void 입찰_validation이_실패하면_claim과_Bid_모두_커밋되지_않는다() {
        Auction auction = persistLiveAuction();
        String url = "/api/auctions/" + auction.getId() + "/bids";

        ResponseEntity<ApiResponse<PlaceBidResponse>> response = restTemplate.exchange(
                url, HttpMethod.POST,
                requestEntity(2L, "rollback-check", 14999L),
                new ParameterizedTypeReference<>() {
                }
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().error().code()).isEqualTo(40904);

        assertThat(bidRepository.countByAuctionId(auction.getId())).isZero();
        assertThat(idempotencyRepository.findByUserIdAndOperationScopeAndIdempotencyKey(
                2L, "PLACE_BID:" + auction.getId(), "rollback-check"
        )).isEmpty();

        Auction reloaded = auctionRepository.findById(auction.getId()).orElseThrow();
        assertThat(reloaded.getCurrentPrice()).isEqualTo(10000L);
        assertThat(reloaded.getCurrentWinner()).isNull();
    }

    @Test
    void 동일_user_auction_amount_key로_동시에_두_번_요청해도_Bid는_1건만_생성된다() throws Exception {
        Auction auction = persistLiveAuction();
        String url = "/api/auctions/" + auction.getId() + "/bids";

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<ResponseEntity<ApiResponse<PlaceBidResponse>>> task = () -> {
            ready.countDown();
            start.await();
            return restTemplate.exchange(
                    url, HttpMethod.POST,
                    requestEntity(2L, "concurrent-abc", 15000L),
                    new ParameterizedTypeReference<>() {
                    }
            );
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ResponseEntity<ApiResponse<PlaceBidResponse>>> future1 = executor.submit(task);
            Future<ResponseEntity<ApiResponse<PlaceBidResponse>>> future2 = executor.submit(task);
            ready.await();
            start.countDown();

            ResponseEntity<ApiResponse<PlaceBidResponse>> response1 = future1.get(30, TimeUnit.SECONDS);
            ResponseEntity<ApiResponse<PlaceBidResponse>> response2 = future2.get(30, TimeUnit.SECONDS);

            assertThat(response1.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(response2.getStatusCode()).isEqualTo(HttpStatus.CREATED);

            Long bidId1 = response1.getBody().data().bidId();
            Long bidId2 = response2.getBody().data().bidId();
            assertThat(bidId1).isEqualTo(bidId2);

            assertThat(bidRepository.countByAuctionId(auction.getId())).isEqualTo(1);
            assertThat(idempotencyRepository.findByUserIdAndOperationScopeAndIdempotencyKey(
                    2L, "PLACE_BID:" + auction.getId(), "concurrent-abc"
            )).isPresent();

            Auction reloaded = auctionRepository.findById(auction.getId()).orElseThrow();
            assertThat(reloaded.getCurrentPrice()).isEqualTo(15000L);
            assertThat(reloaded.getCurrentWinner().getId()).isEqualTo(2L);
            assertThat(reloaded.getCurrentWinner().getId()).isEqualTo(bidRepository.findById(bidId1).orElseThrow().getUser().getId());
        } finally {
            executor.shutdown();
        }
    }
}
