package com.vintic.backend.concurrency;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.autobid.domain.AutoBidSetting;
import com.vintic.backend.autobid.domain.AutoBidSettingStatus;
import com.vintic.backend.autobid.domain.EffectiveCapCalculator;
import com.vintic.backend.autobid.dto.AutoBidRegisterResponse;
import com.vintic.backend.autobid.dto.AutoBidUpdateResponse;
import com.vintic.backend.autobid.repository.AutoBidSettingRepository;
import com.vintic.backend.bid.domain.Bid;
import com.vintic.backend.bid.dto.PlaceBidResponse;
import com.vintic.backend.bid.repository.BidRepository;
import com.vintic.backend.common.dto.ApiResponse;
import com.vintic.backend.product.domain.Product;
import com.vintic.backend.product.repository.ProductRepository;
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

// #45: Manual/AutoBid 혼합 동시 요청을 실제 MySQL(Testcontainers, InnoDB)로 검증한다.
// AutoBidConcurrencyMySqlIT/ManualBidIdempotencyMySqlIT와 동일한 harness(TestRestTemplate +
// CountDownLatch + ExecutorService)를 재사용하고 새 harness를 만들지 않는다.
//
// findByIdForUpdate(PESSIMISTIC_WRITE)가 두 요청을 실질적으로 직렬화하므로, "어느 쪽이 실제로
// 먼저 커밋됐는가"는 테스트가 통제할 수 없다 - 그래서 하나의 고정된 기대값을 assert하지 않고,
// 실행 순서와 무관하게 항상 성립해야 하는 invariant(가격 단조증가/승자 cap 초과 금지/
// currentWinner-영속 Bid 일치/부분 commit 없음)만 검증한다. 기존 순수 Proxy invariant
// (ProxyPriceEngineTest)를 여기서 중복 재작성하지 않는다 - 이 클래스의 관심사는 동시 실행 후
// persistence 정합성이다.
//
// 사용자 ID 1/2/3은 LocalUserSeeder가 미리 심어두는 더미 유저다 - 이 클래스가 만드는 seller는
// 매 테스트 persistLiveAuction()에서 새로 저장되므로 auto-increment가 4 이상을 받는다(로컬
// 프로필 기준). 그래서 1/2/3을 매 테스트의 "실제 참가자" 식별자로 안전하게 재사용할 수 있다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@Testcontainers
class ProxyMixedConcurrencyMySqlIT {

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

    @Autowired
    private BidRepository bidRepository;

    private Auction persistLiveAuction(long startPrice, long bidIncrement) {
        var seller = userRepository.save(com.vintic.backend.user.domain.User.register(
                "seller-" + System.identityHashCode(new Object()) + "@vintic.local", "seller", null
        ));
        Product product = productRepository.save(new Product(
                seller,
                List.of("https://example.com/a.jpg"),
                "Nike", "Dunk Low", "Panda", 270, "B", "PARTIAL",
                300000, 350000, "285,000원 ~ 315,000원", 290000, "사유", "설명"
        ));
        Auction auction = Auction.schedule(
                product, startPrice, bidIncrement, LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(1)
        );
        auction.start();
        return auctionRepository.save(auction);
    }

    private HttpEntity<Map<String, Object>> bidRequest(Long userId, String idempotencyKey, Long amount) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-User-Id", String.valueOf(userId));
        headers.set("Idempotency-Key", idempotencyKey);
        return new HttpEntity<>(Map.of("amount", amount), headers);
    }

    private HttpEntity<Map<String, Object>> autoBidRequest(Long userId, String idempotencyKey, Long maxAmount) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-User-Id", String.valueOf(userId));
        headers.set("Idempotency-Key", idempotencyKey);
        return new HttpEntity<>(Map.of("maxAmount", maxAmount), headers);
    }

    @Test
    void Manual_Bid와_LIVE_AutoBid_CREATE가_동시에_들어와도_최종_상태는_invariant를_지킨다() throws Exception {
        Auction auction = persistLiveAuction(105000L, 5000L); // minNextBidAmount=110000
        String bidsUrl = "/api/auctions/" + auction.getId() + "/bids";
        String autoBidsUrl = "/api/auctions/" + auction.getId() + "/auto-bids";

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<ResponseEntity<ApiResponse<PlaceBidResponse>>> manualTask = () -> {
            ready.countDown();
            start.await();
            return restTemplate.exchange(
                    bidsUrl, HttpMethod.POST, bidRequest(1L, "mixed-manual", 110000L),
                    new ParameterizedTypeReference<>() {
                    }
            );
        };
        Callable<ResponseEntity<ApiResponse<AutoBidRegisterResponse>>> autoBidTask = () -> {
            ready.countDown();
            start.await();
            return restTemplate.exchange(
                    autoBidsUrl, HttpMethod.POST, autoBidRequest(2L, "mixed-auto-create", 200000L),
                    new ParameterizedTypeReference<>() {
                    }
            );
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ResponseEntity<ApiResponse<PlaceBidResponse>>> manualFuture = executor.submit(manualTask);
            Future<ResponseEntity<ApiResponse<AutoBidRegisterResponse>>> autoBidFuture = executor.submit(autoBidTask);
            ready.await();
            start.countDown();

            ResponseEntity<ApiResponse<PlaceBidResponse>> manualResponse = manualFuture.get(30, TimeUnit.SECONDS);
            ResponseEntity<ApiResponse<AutoBidRegisterResponse>> autoBidResponse = autoBidFuture.get(30, TimeUnit.SECONDS);

            // 둘 다 유효한 command였으므로 lock 경합으로 500/409가 나서는 안 된다 - 순서만 직렬화될 뿐이다.
            assertThat(manualResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(autoBidResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

            assertPostStateInvariants(auction.getId(), 105000L);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void Manual_Bid와_LIVE_AutoBid_cap_UPDATE가_동시에_들어와도_최종_상태는_invariant를_지킨다() throws Exception {
        Auction auction = persistLiveAuction(105000L, 5000L);
        AutoBidSetting existing = AutoBidSetting.reserve(auction, userRepository.findById(3L).orElseThrow(), 110000L);
        existing.activate();
        existing.markCapReached(); // 상향해야만 다시 경쟁 가능한 상태에서 출발
        autoBidSettingRepository.saveAndFlush(existing);

        String bidsUrl = "/api/auctions/" + auction.getId() + "/bids";
        String autoBidUpdateUrl = "/api/auctions/" + auction.getId() + "/auto-bids/me";

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<ResponseEntity<ApiResponse<PlaceBidResponse>>> manualTask = () -> {
            ready.countDown();
            start.await();
            return restTemplate.exchange(
                    bidsUrl, HttpMethod.POST, bidRequest(1L, "mixed-manual-vs-update", 120000L),
                    new ParameterizedTypeReference<>() {
                    }
            );
        };
        Callable<ResponseEntity<ApiResponse<AutoBidUpdateResponse>>> updateTask = () -> {
            ready.countDown();
            start.await();
            return restTemplate.exchange(
                    autoBidUpdateUrl, HttpMethod.PATCH, autoBidRequest(3L, "mixed-auto-update", 300000L),
                    new ParameterizedTypeReference<>() {
                    }
            );
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ResponseEntity<ApiResponse<PlaceBidResponse>>> manualFuture = executor.submit(manualTask);
            Future<ResponseEntity<ApiResponse<AutoBidUpdateResponse>>> updateFuture = executor.submit(updateTask);
            ready.await();
            start.countDown();

            ResponseEntity<ApiResponse<PlaceBidResponse>> manualResponse = manualFuture.get(30, TimeUnit.SECONDS);
            ResponseEntity<ApiResponse<AutoBidUpdateResponse>> updateResponse = updateFuture.get(30, TimeUnit.SECONDS);

            assertThat(manualResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(updateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

            assertPostStateInvariants(auction.getId(), 105000L);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void 복수_사용자의_AutoBid_등록이_동시에_들어와도_최종_상태는_invariant를_지킨다() throws Exception {
        Auction auction = persistLiveAuction(105000L, 5000L);
        String autoBidsUrl = "/api/auctions/" + auction.getId() + "/auto-bids";

        CountDownLatch ready = new CountDownLatch(3);
        CountDownLatch start = new CountDownLatch(1);
        Callable<ResponseEntity<ApiResponse<AutoBidRegisterResponse>>> taskA = () -> {
            ready.countDown();
            start.await();
            return restTemplate.exchange(
                    autoBidsUrl, HttpMethod.POST, autoBidRequest(1L, "mixed-multi-a", 150000L),
                    new ParameterizedTypeReference<>() {
                    }
            );
        };
        Callable<ResponseEntity<ApiResponse<AutoBidRegisterResponse>>> taskB = () -> {
            ready.countDown();
            start.await();
            return restTemplate.exchange(
                    autoBidsUrl, HttpMethod.POST, autoBidRequest(2L, "mixed-multi-b", 300000L),
                    new ParameterizedTypeReference<>() {
                    }
            );
        };
        Callable<ResponseEntity<ApiResponse<AutoBidRegisterResponse>>> taskC = () -> {
            ready.countDown();
            start.await();
            return restTemplate.exchange(
                    autoBidsUrl, HttpMethod.POST, autoBidRequest(3L, "mixed-multi-c", 200000L),
                    new ParameterizedTypeReference<>() {
                    }
            );
        };

        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            Future<ResponseEntity<ApiResponse<AutoBidRegisterResponse>>> futureA = executor.submit(taskA);
            Future<ResponseEntity<ApiResponse<AutoBidRegisterResponse>>> futureB = executor.submit(taskB);
            Future<ResponseEntity<ApiResponse<AutoBidRegisterResponse>>> futureC = executor.submit(taskC);
            ready.await();
            start.countDown();

            List<ResponseEntity<ApiResponse<AutoBidRegisterResponse>>> responses = List.of(
                    futureA.get(30, TimeUnit.SECONDS), futureB.get(30, TimeUnit.SECONDS), futureC.get(30, TimeUnit.SECONDS)
            );
            for (var response : responses) {
                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            }

            // 최강 cap(B, 300000)이 최종 승자여야 하고, 나머지 둘은 CAP_REACHED여야 한다 -
            // 3파전은 최강/차강 cap만으로 가격이 정해진다(ProxyPriceEngineTest에서 이미 검증된 규칙,
            // 여기서는 실제 concurrent DB 실행 후에도 그 결과가 유지되는지만 본다).
            List<AutoBidSetting> settings = autoBidSettingRepository.findAll().stream()
                    .filter(s -> s.getAuction().getId().equals(auction.getId()))
                    .toList();
            assertThat(settings).hasSize(3);
            long activeCount = settings.stream().filter(s -> s.getStatus() == AutoBidSettingStatus.ACTIVE).count();
            long capReachedCount = settings.stream().filter(s -> s.getStatus() == AutoBidSettingStatus.CAP_REACHED).count();
            assertThat(activeCount).isEqualTo(1);
            assertThat(capReachedCount).isEqualTo(2);

            AutoBidSetting winner = settings.stream()
                    .filter(s -> s.getStatus() == AutoBidSettingStatus.ACTIVE)
                    .findFirst().orElseThrow();
            assertThat(winner.getUser().getId()).isEqualTo(2L); // 최강 cap(300000)을 등록한 사용자

            assertPostStateInvariants(auction.getId(), 105000L);
        } finally {
            executor.shutdown();
        }
    }

    // 실행 순서와 무관하게 항상 성립해야 하는 invariant들 - 트랜잭션 종료 후 DB를 다시 조회해서 검증한다.
    private void assertPostStateInvariants(Long auctionId, long baselinePrice) {
        Auction reloaded = auctionRepository.findById(auctionId).orElseThrow();

        // 1) currentPrice는 감소하지 않는다.
        assertThat(reloaded.getCurrentPrice()).isGreaterThanOrEqualTo(baselinePrice);

        // 2) currentWinner가 있다면, 그 winner 명의로 저장된 마지막(가장 늦게 생성된) Bid의 bidder와 일치한다.
        List<Bid> bids = bidRepository.findAll().stream()
                .filter(b -> b.getAuction().getId().equals(auctionId))
                .sorted((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()))
                .toList();
        assertThat(bids).isNotEmpty();
        Bid lastBid = bids.get(bids.size() - 1);
        assertThat(reloaded.getCurrentWinner()).isNotNull();
        assertThat(reloaded.getCurrentWinner().getId()).isEqualTo(lastBid.getUser().getId());
        assertThat(reloaded.getCurrentPrice()).isEqualTo(lastBid.getAmount());

        // 3) winner가 AutoBid로 뒷받침된다면, 최종 가격은 그 AutoBid의 effectiveCap을 넘지 않는다.
        autoBidSettingRepository.findByAuctionIdAndUserIdAndActiveSlotTrue(auctionId, reloaded.getCurrentWinner().getId())
                .ifPresent(winnerSetting -> {
                    long effectiveCap = EffectiveCapCalculator.calculate(
                            winnerSetting.getMaxAmount(), baselinePrice, reloaded.getBidIncrement()
                    );
                    assertThat(reloaded.getCurrentPrice()).isLessThanOrEqualTo(effectiveCap);
                });
    }
}
