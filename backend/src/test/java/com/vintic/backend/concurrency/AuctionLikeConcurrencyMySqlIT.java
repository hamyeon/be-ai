package com.vintic.backend.concurrency;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.common.dto.ApiResponse;
import com.vintic.backend.like.dto.LikeResponse;
import com.vintic.backend.like.repository.AuctionLikeRepository;
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

// #55: AuctionLike는 (auction, user) 조합당 최대 1개만 존재해야 한다. Service의 사전
// exists-check만으로는 동시 요청 race를 막지 못하므로 uk_auction_like_auction_user UNIQUE
// 제약을 최종 방어선으로 뒀다(AuctionLikeService) - 이 테스트는 같은 사용자가 정확히 같은
// 순간에 두 번 좋아요를 눌러도(같은 auction, 같은 user, 서로 다른 요청) 실제 MySQL에서 row가
// 1개만 남는지, 그리고 두 응답 모두 500이 아니라 정상 응답(liked=true)으로 끝나는지 확인한다 -
// AutoBidConcurrencyMySqlIT/ManualBidIdempotencyMySqlIT와 동일한 harness를 재사용한다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@Testcontainers
class AuctionLikeConcurrencyMySqlIT {

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
    private AuctionLikeRepository auctionLikeRepository;

    private Auction persistAuction() {
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

    private HttpEntity<Void> likeRequest(Long userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", String.valueOf(userId));
        return new HttpEntity<>(headers);
    }

    @Test
    void 같은_사용자가_동시에_두_번_좋아요해도_row는_1개만_생성된다() throws Exception {
        Auction auction = persistAuction();
        String url = "/api/auctions/" + auction.getId() + "/likes";

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<ResponseEntity<ApiResponse<LikeResponse>>> task = () -> {
            ready.countDown();
            start.await();
            return restTemplate.exchange(
                    url, HttpMethod.POST, likeRequest(2L),
                    new ParameterizedTypeReference<>() {
                    }
            );
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ResponseEntity<ApiResponse<LikeResponse>>> future1 = executor.submit(task);
            Future<ResponseEntity<ApiResponse<LikeResponse>>> future2 = executor.submit(task);
            ready.await();
            start.countDown();

            ResponseEntity<ApiResponse<LikeResponse>> response1 = future1.get(30, TimeUnit.SECONDS);
            ResponseEntity<ApiResponse<LikeResponse>> response2 = future2.get(30, TimeUnit.SECONDS);

            // 동시 요청이라도 둘 다 500(락/유니크 예외 누출)이 아니라 정상 200/liked=true로
            // 끝나야 한다 - 계약상 중복 좋아요에 대한 에러가 정의돼 있지 않으므로(§19) 멱등
            // 정책대로 진 쪽도 성공으로 흡수된다.
            assertThat(response1.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response2.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response1.getBody().data().liked()).isTrue();
            assertThat(response2.getBody().data().liked()).isTrue();

            long rowsForThisAuction = auctionLikeRepository.countByAuctionId(auction.getId());
            assertThat(rowsForThisAuction)
                    .as("uk_auction_like_auction_user가 동시 요청에서도 row 1개만 남기는지")
                    .isEqualTo(1);

            // 두 응답 모두 최종 likeCount를 정확히 1로 봐야 한다(둘 중 어느 쪽이 실제로 INSERT를
            // 했든, 나머지 하나는 그 커밋 이후 count를 다시 읽는다).
            assertThat(response1.getBody().data().likeCount()).isEqualTo(1);
            assertThat(response2.getBody().data().likeCount()).isEqualTo(1);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void 서로_다른_사용자의_동시_좋아요는_각각_반영된다() throws Exception {
        Auction auction = persistAuction();
        String url = "/api/auctions/" + auction.getId() + "/likes";

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<ResponseEntity<ApiResponse<LikeResponse>>> taskA = () -> {
            ready.countDown();
            start.await();
            return restTemplate.exchange(
                    url, HttpMethod.POST, likeRequest(1L),
                    new ParameterizedTypeReference<>() {
                    }
            );
        };
        Callable<ResponseEntity<ApiResponse<LikeResponse>>> taskB = () -> {
            ready.countDown();
            start.await();
            return restTemplate.exchange(
                    url, HttpMethod.POST, likeRequest(2L),
                    new ParameterizedTypeReference<>() {
                    }
            );
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ResponseEntity<ApiResponse<LikeResponse>>> futureA = executor.submit(taskA);
            Future<ResponseEntity<ApiResponse<LikeResponse>>> futureB = executor.submit(taskB);
            ready.await();
            start.countDown();

            ResponseEntity<ApiResponse<LikeResponse>> responseA = futureA.get(30, TimeUnit.SECONDS);
            ResponseEntity<ApiResponse<LikeResponse>> responseB = futureB.get(30, TimeUnit.SECONDS);

            assertThat(responseA.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(responseB.getStatusCode()).isEqualTo(HttpStatus.OK);

            long rowsForThisAuction = auctionLikeRepository.countByAuctionId(auction.getId());
            assertThat(rowsForThisAuction).isEqualTo(2);
        } finally {
            executor.shutdown();
        }
    }
}
