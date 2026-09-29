package com.vintic.backend.auction;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.dto.AuctionRelistResponse;
import com.vintic.backend.auction.repository.AuctionRepository;
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

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

// 재경매(POST /api/auctions/{previousAuctionId}/relist) 동시성 검증. previousAuctionId가
// 시작 전 취소된(CANCELED) 유일한 이전 경매를 가리키는 상황에서 두 요청이 동시에 Product row
// lock(findByIdForUpdate) 사전 조회를 통과하려는 race를 실제 MySQL(InnoDB, Testcontainers)로
// 재현한다 - 이 한 시나리오가 "SCHEDULED/LIVE 동시 1건" 불변식과 "총 2회 제한" 불변식을 함께
// 검증한다: 승자가 커밋하면 총 등록 횟수가 2가 되는 동시에 활성 경매가 생기므로, 패자는 재확인 시
// hasActive 체크에서 먼저 걸린다(활성 경매 존재가 총 횟수 초과보다 먼저 판정되는 코드 순서,
// AuctionRelistService.relist() 참고). 순차 케이스는 AuctionRelistServiceTest(Mockito)로 이미
// 충분하다 - H2로는 이 race window를 신뢰성 있게 재현할 수 없다(ManualBidIdempotencyMySqlIT와
// 동일 이유).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@Testcontainers
class AuctionRelistConcurrencyMySqlIT {

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

    private HttpEntity<Map<String, Object>> requestEntity(Long userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-User-Id", String.valueOf(userId));
        OffsetDateTime startAt = OffsetDateTime.now().plusHours(2);
        OffsetDateTime endAt = startAt.plusHours(2);
        return new HttpEntity<>(Map.of(
                "startPrice", 10000,
                "startAt", startAt.toString(),
                "endAt", endAt.toString()
        ), headers);
    }

    @Test
    void 같은_이전_경매로_동시에_재경매해도_정확히_1건만_생성되고_총_등록횟수는_2를_초과하지_않는다() throws Exception {
        User seller = userRepository.save(User.register(
                "seller-" + System.identityHashCode(new Object()) + "@vintic.local", "seller", null
        ));
        Product product = productRepository.save(new Product(
                seller,
                List.of("https://example.com/a.jpg"),
                "Nike", "Dunk Low", "Panda", 270, "B", "PARTIAL",
                300000, 350000, "285,000원 ~ 315,000원", 290000, "사유", "설명"
        ));
        // 첫 경매를 시작 전 취소된 상태로 만들어 둔다(남은 등록 횟수 1회).
        Auction firstCanceled = Auction.schedule(
                product, 10000L, 5000L,
                java.time.LocalDateTime.now().plusDays(1), java.time.LocalDateTime.now().plusDays(1).plusHours(2)
        );
        firstCanceled.cancel();
        Auction saved = auctionRepository.save(firstCanceled);

        String url = "/api/auctions/" + saved.getId() + "/relist";

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<ResponseEntity<ApiResponse<AuctionRelistResponse>>> task = () -> {
            ready.countDown();
            start.await();
            return restTemplate.exchange(
                    url, HttpMethod.POST, requestEntity(seller.getId()), new ParameterizedTypeReference<>() {
                    }
            );
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ResponseEntity<ApiResponse<AuctionRelistResponse>>> futureA = executor.submit(task);
            Future<ResponseEntity<ApiResponse<AuctionRelistResponse>>> futureB = executor.submit(task);
            ready.await();
            start.countDown();

            ResponseEntity<ApiResponse<AuctionRelistResponse>> responseA = futureA.get(30, TimeUnit.SECONDS);
            ResponseEntity<ApiResponse<AuctionRelistResponse>> responseB = futureB.get(30, TimeUnit.SECONDS);
            List<ResponseEntity<ApiResponse<AuctionRelistResponse>>> responses = List.of(responseA, responseB);

            long createdCount = responses.stream().filter(r -> r.getStatusCode() == HttpStatus.CREATED).count();
            long conflictCount = responses.stream().filter(r -> r.getStatusCode() == HttpStatus.CONFLICT).count();
            assertThat(createdCount).isEqualTo(1);
            assertThat(conflictCount).isEqualTo(1);

            ResponseEntity<ApiResponse<AuctionRelistResponse>> conflict = responseA.getStatusCode() == HttpStatus.CONFLICT
                    ? responseA : responseB;
            // DB unique 제약(uk_auction_product_active_slot) 위반이 500으로 새지 않고 40918로
            // 변환됐는지 확인 - 패자는 승자가 커밋한 뒤 재확인하면 활성 경매가 이미 존재하는
            // 상태를 보게 된다.
            assertThat(conflict.getBody().error().code()).isEqualTo(40918);

            List<Auction> allForProduct = auctionRepository.findAllByProductId(product.getId());
            assertThat(allForProduct).hasSize(2);
            long activeCount = allForProduct.stream()
                    .filter(a -> a.getActiveSlot() != null && a.getActiveSlot())
                    .count();
            assertThat(activeCount).isEqualTo(1);
        } finally {
            executor.shutdown();
        }
    }
}
