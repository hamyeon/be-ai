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

    // #46 follow-up: 이 테스트는 원래 "3명 모두 항상 201"을 assert했으나, 이는 domain invariant가
    // 아니라 실행 순서 의존적인 잘못된 가정이었다 - 20회 반복 실측에서 약 1/3 확률로 재현되는
    // 실패를 발견해 원인을 규명했다(아래 주석 및 contract-gap.md #46 notes 참고). Proxy 가격
    // 정책 자체는 바꾸지 않고, 이 테스트의 assertion만 실제 domain invariant에 맞게 고쳤다.
    //
    // cap 구성(A=150000, B=300000, C=200000)에서 pairwise 최대 상승폭을 계산하면:
    //   - B(300000)는 A/C가 먼저 경쟁해도 그 결과 가격이 최대 160000(=155000+5000)까지만
    //     오르므로 항상 minCap을 충족한다 - 항상 201이어야 한다.
    //   - C(200000)도 같은 이유로 A/B가 먼저 경쟁해도 그 결과 minCap이 최대 160000이라 항상
    //     충족한다 - 항상 201이어야 한다.
    //   - A(150000)만 B/C가 "둘 다" 먼저 경쟁한 뒤에 등록되면(price가 205000까지 올라
    //     minCap=210000) 정당하게 40906 CAP_TOO_LOW로 거절될 수 있다 - Auction 락이 세 요청을
    //     직렬화하는 순서(6가지 중 A가 마지막인 2가지)에 따라 갈리는 정상적인 결과다.
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

            ResponseEntity<ApiResponse<AutoBidRegisterResponse>> responseA = futureA.get(30, TimeUnit.SECONDS);
            ResponseEntity<ApiResponse<AutoBidRegisterResponse>> responseB = futureB.get(30, TimeUnit.SECONDS);
            ResponseEntity<ApiResponse<AutoBidRegisterResponse>> responseC = futureC.get(30, TimeUnit.SECONDS);

            assertThat(responseB.getStatusCode())
                    .as("최강 cap(300000)은 실행 순서와 무관하게 항상 등록에 성공해야 한다(500/DB exception 누출 금지)")
                    .isEqualTo(HttpStatus.CREATED);
            assertThat(responseC.getStatusCode())
                    .as("차강 cap(200000)은 실행 순서와 무관하게 항상 등록에 성공해야 한다(500/DB exception 누출 금지)")
                    .isEqualTo(HttpStatus.CREATED);

            // 최약 cap(150000)은 등록 시점 minCap을 충족하면 201, 앞선 B/C 경쟁으로 충족 못하면
            // 409/40906만 정상이다 - 그 외(다른 코드, 500)는 실패다.
            assertThat(responseA.getStatusCode())
                    .as("cap=150000은 minCap 충족 여부에 따라 201 또는 409만 허용된다")
                    .isIn(HttpStatus.CREATED, HttpStatus.CONFLICT);
            boolean aRejectedByCapTooLow = responseA.getStatusCode() == HttpStatus.CONFLICT;
            if (aRejectedByCapTooLow) {
                assertThat(responseA.getBody().success()).isFalse();
                assertThat(responseA.getBody().error().code())
                        .as("A가 거절됐다면 반드시 40906(CAP_TOO_LOW)이어야 한다 - 다른 409/500은 진짜 결함이다")
                        .isEqualTo(40906);
            }

            // 핵심 assertion은 응답 성공 개수가 아니라 트랜잭션 종료 후 DB post-state다.
            List<AutoBidSetting> settings = autoBidSettingRepository.findAll().stream()
                    .filter(s -> s.getAuction().getId().equals(auction.getId()))
                    .toList();
            assertThat(settings)
                    .as("40906으로 거절된 CREATE는 current AutoBidSetting row를 남기지 않아야 한다")
                    .hasSize(aRejectedByCapTooLow ? 2 : 3);

            long activeCount = settings.stream().filter(s -> s.getStatus() == AutoBidSettingStatus.ACTIVE).count();
            assertThat(activeCount)
                    .as("ACTIVE AutoBidSetting은 최대 1개여야 한다")
                    .isEqualTo(1);

            AutoBidSetting winner = settings.stream()
                    .filter(s -> s.getStatus() == AutoBidSettingStatus.ACTIVE)
                    .findFirst().orElseThrow();
            assertThat(winner.getUser().getId())
                    .as("최강 cap(300000, user 2)이 실행 순서와 무관하게 최종 ACTIVE/winner여야 한다")
                    .isEqualTo(2L);

            // winner가 아닌 나머지 영속 row는(등록에 성공했다면) 전부 CAP_REACHED여야 한다.
            List<AutoBidSetting> nonWinners = settings.stream()
                    .filter(s -> s.getStatus() != AutoBidSettingStatus.ACTIVE)
                    .toList();
            for (AutoBidSetting nonWinner : nonWinners) {
                assertThat(nonWinner.getStatus())
                        .as("winner가 아닌 영속 설정은 CAP_REACHED여야 한다: userId=" + nonWinner.getUser().getId())
                        .isEqualTo(AutoBidSettingStatus.CAP_REACHED);
            }

            if (aRejectedByCapTooLow) {
                assertThat(autoBidSettingRepository.findByAuctionIdAndUserIdAndActiveSlotTrue(auction.getId(), 1L))
                        .as("40906으로 거절된 user 1은 current AutoBidSetting이 없어야 한다")
                        .isEmpty();
            }

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
