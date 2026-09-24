package com.vintic.backend.purchasegoal;

import com.vintic.backend.ai.purchase.dto.GoalCondition;
import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.autobid.domain.AutoBidSetting;
import com.vintic.backend.autobid.repository.AutoBidSettingRepository;
import com.vintic.backend.autobid.service.AutoBidCommandService;
import com.vintic.backend.common.exception.AuctionClosedException;
import com.vintic.backend.common.exception.AutoBidAlreadyExistsException;
import com.vintic.backend.common.exception.CapTooLowException;
import com.vintic.backend.product.domain.Product;
import com.vintic.backend.product.repository.ProductRepository;
import com.vintic.backend.purchasegoal.domain.PurchaseGoal;
import com.vintic.backend.purchasegoal.domain.PurchaseGoalStatus;
import com.vintic.backend.purchasegoal.dto.PurchaseGoalCancelResponse;
import com.vintic.backend.purchasegoal.repository.PurchaseGoalRepository;
import com.vintic.backend.purchasegoal.service.PurchaseGoalCommandService;
import com.vintic.backend.purchasegoal.service.PurchaseGoalEngagementResult;
import com.vintic.backend.purchasegoal.service.PurchaseGoalEngagementTransactionService;
import com.vintic.backend.user.domain.User;
import com.vintic.backend.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Day 5: Goal ACTIVE->ENGAGED 조건부 전이 + AutoBid 생성의 원자성/동시성만 실제 MySQL로 검증한다.
// @DataJpaTest(테스트 전체가 하나의 롤백 트랜잭션)를 쓰지 않는다 - PurchaseGoalEngagementTransactionService.
// engage()가 실제로 commit/rollback하는지 확인하려면 진짜 트랜잭션 경계가 필요한데, @DataJpaTest 안에서는
// 내부 @Transactional(REQUIRED) 호출이 예외를 던져도 바깥 테스트 트랜잭션이 rollback-only로 표시될
// 뿐 실제 롤백은 테스트 종료 시점에야 일어나 같은 트랜잭션 안에서의 재조회로는 확인할 수 없다
// (AutoBidConcurrencyMySqlIT와 동일한 이유로 실제 MySQL/Testcontainers를 쓴다).
//
// 실행: OPENAI_API_KEY=dummy ./gradlew test --tests "...PurchaseGoalEngagementMySqlIT"
// (@SpringBootTest가 전체 컨텍스트를 띄우면서 OpenAiGoalParser/OpenAiListingMatcher 빈 생성에
// API 키 프로퍼티가 필요하다 - 실제로 호출하지는 않는다.)
@SpringBootTest
@ActiveProfiles("local")
@Testcontainers
class PurchaseGoalEngagementMySqlIT {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired
    private PurchaseGoalEngagementTransactionService engagementTransactionService;

    @Autowired
    private PurchaseGoalCommandService purchaseGoalCommandService;

    @Autowired
    private AutoBidCommandService autoBidCommandService;

    @Autowired
    private PurchaseGoalRepository purchaseGoalRepository;

    @Autowired
    private AutoBidSettingRepository autoBidSettingRepository;

    @Autowired
    private AuctionRepository auctionRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private UserRepository userRepository;

    private User persistUser(String email) {
        return userRepository.save(User.register(email, email, null));
    }

    private Product persistProduct(User seller) {
        return productRepository.save(new Product(
                seller, List.of("https://example.com/a.jpg"),
                "New Balance", "990v6", "Grey", 270, "A", "PARTIAL",
                300000, 350000, "280,000원 ~ 320,000원", 290000, "사유", "설명"
        ));
    }

    private Auction persistLiveAuction(Product product, long startPrice, long bidIncrement) {
        Auction auction = Auction.schedule(
                product, startPrice, bidIncrement, LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(1)
        );
        auction.start();
        return auctionRepository.save(auction);
    }

    private PurchaseGoal persistActiveGoal(User owner, Long hardMaxAmount) {
        LocalDateTime now = LocalDateTime.now();
        PurchaseGoal goal = PurchaseGoal.create(
                owner, "New Balance", "nb990", "뉴발란스 990",
                GoalCondition.B, null, hardMaxAmount, null,
                now.plusDays(7), now
        );
        return purchaseGoalRepository.save(goal);
    }

    @Test
    void 참여에_성공하면_Goal은_ENGAGED로_전이되고_AutoBid에_purchaseGoalId가_연결된다() {
        User seller = persistUser("seller1@vintic.local");
        User buyer = persistUser("buyer1@vintic.local");
        Auction auction = persistLiveAuction(persistProduct(seller), 100000L, 5000L); // minNextBidAmount=105000
        PurchaseGoal goal = persistActiveGoal(buyer, 500000L);

        PurchaseGoalEngagementResult result = engagementTransactionService.engage(goal.getId(), auction.getId(), buyer.getId(), 200000L);

        assertThat(result.engaged()).isTrue();
        assertThat(result.autoBidSettingId()).isNotNull();

        PurchaseGoal reloadedGoal = purchaseGoalRepository.findById(goal.getId()).orElseThrow();
        assertThat(reloadedGoal.getStatus()).isEqualTo(PurchaseGoalStatus.ENGAGED);
        assertThat(reloadedGoal.getCurrentAuctionId()).isEqualTo(auction.getId());

        AutoBidSetting setting = autoBidSettingRepository.findByAuctionIdAndUserIdAndActiveSlotTrue(auction.getId(), buyer.getId())
                .orElseThrow();
        assertThat(setting.getPurchaseGoalId()).isEqualTo(goal.getId());
        assertThat(setting.getMaxAmount()).isEqualTo(200000L);
    }

    @Test
    void 일반_AutoBid_등록은_purchaseGoalId_없이_그대로_동작한다() {
        User seller = persistUser("seller2@vintic.local");
        User buyer = persistUser("buyer2@vintic.local");
        Auction auction = persistLiveAuction(persistProduct(seller), 100000L, 5000L);

        autoBidCommandService.createAutoBid(auction.getId(), buyer.getId(), 200000L);

        AutoBidSetting setting = autoBidSettingRepository.findByAuctionIdAndUserIdAndActiveSlotTrue(auction.getId(), buyer.getId())
                .orElseThrow();
        assertThat(setting.getPurchaseGoalId()).isNull();
    }

    @Test
    void 경매가_이미_종료됐으면_참여는_실패하고_Goal은_ACTIVE로_남는다() {
        User seller = persistUser("seller3@vintic.local");
        User buyer = persistUser("buyer3@vintic.local");
        Auction auction = persistLiveAuction(persistProduct(seller), 100000L, 5000L);
        auction.end();
        auctionRepository.save(auction);
        PurchaseGoal goal = persistActiveGoal(buyer, 500000L);

        assertThatThrownBy(() -> engagementTransactionService.engage(goal.getId(), auction.getId(), buyer.getId(), 200000L))
                .isInstanceOf(AuctionClosedException.class);

        assertThat(purchaseGoalRepository.findById(goal.getId()).orElseThrow().getStatus()).isEqualTo(PurchaseGoalStatus.ACTIVE);
        assertThat(autoBidSettingRepository.findByAuctionIdAndUserIdAndActiveSlotTrue(auction.getId(), buyer.getId())).isEmpty();
    }

    @Test
    void 가격이_올라_cap이_최소_입찰_금액에_못_미치면_참여는_실패하고_Goal은_ACTIVE로_남는다() {
        User seller = persistUser("seller4@vintic.local");
        User buyer = persistUser("buyer4@vintic.local");
        // currentPrice=99000, bidIncrement=5000 -> minNextBidAmount=104000
        Auction auction = persistLiveAuction(persistProduct(seller), 99000L, 5000L);
        PurchaseGoal goal = persistActiveGoal(buyer, 500000L);

        assertThatThrownBy(() -> engagementTransactionService.engage(goal.getId(), auction.getId(), buyer.getId(), 100000L))
                .isInstanceOf(CapTooLowException.class);

        assertThat(purchaseGoalRepository.findById(goal.getId()).orElseThrow().getStatus()).isEqualTo(PurchaseGoalStatus.ACTIVE);
        assertThat(autoBidSettingRepository.findByAuctionIdAndUserIdAndActiveSlotTrue(auction.getId(), buyer.getId())).isEmpty();
    }

    @Test
    void 이미_같은_경매에_AutoBid가_있으면_참여는_실패하고_Goal은_ACTIVE로_남는다() {
        User seller = persistUser("seller5@vintic.local");
        User buyer = persistUser("buyer5@vintic.local");
        Auction auction = persistLiveAuction(persistProduct(seller), 100000L, 5000L);
        autoBidCommandService.createAutoBid(auction.getId(), buyer.getId(), 150000L);
        PurchaseGoal goal = persistActiveGoal(buyer, 500000L);

        assertThatThrownBy(() -> engagementTransactionService.engage(goal.getId(), auction.getId(), buyer.getId(), 200000L))
                .isInstanceOf(AutoBidAlreadyExistsException.class);

        assertThat(purchaseGoalRepository.findById(goal.getId()).orElseThrow().getStatus()).isEqualTo(PurchaseGoalStatus.ACTIVE);
        long rowsForThisAuction = autoBidSettingRepository.findAll().stream()
                .filter(setting -> setting.getAuction().getId().equals(auction.getId()))
                .count();
        assertThat(rowsForThisAuction).isEqualTo(1);
    }

    @Test
    void 동시에_engage를_두_번_시도해도_하나만_성공한다() throws Exception {
        User seller = persistUser("seller6@vintic.local");
        User buyer = persistUser("buyer6@vintic.local");
        Auction auction = persistLiveAuction(persistProduct(seller), 100000L, 5000L);
        PurchaseGoal goal = persistActiveGoal(buyer, 500000L);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<PurchaseGoalEngagementResult> task = () -> {
            ready.countDown();
            start.await();
            return engagementTransactionService.engage(goal.getId(), auction.getId(), buyer.getId(), 200000L);
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<PurchaseGoalEngagementResult> future1 = executor.submit(task);
            Future<PurchaseGoalEngagementResult> future2 = executor.submit(task);
            ready.await();
            start.countDown();

            PurchaseGoalEngagementResult result1 = future1.get(30, TimeUnit.SECONDS);
            PurchaseGoalEngagementResult result2 = future2.get(30, TimeUnit.SECONDS);

            long engagedCount = List.of(result1, result2).stream().filter(PurchaseGoalEngagementResult::engaged).count();
            assertThat(engagedCount).isEqualTo(1);

            assertThat(purchaseGoalRepository.findById(goal.getId()).orElseThrow().getStatus()).isEqualTo(PurchaseGoalStatus.ENGAGED);
            long rowsForThisAuction = autoBidSettingRepository.findAll().stream()
                    .filter(setting -> setting.getAuction().getId().equals(auction.getId()))
                    .count();
            assertThat(rowsForThisAuction).isEqualTo(1);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void 참여가_먼저_커밋되면_취소는_CANCEL_REQUESTED로_떨어지고_AutoBid는_그대로_남는다() {
        User seller = persistUser("seller7@vintic.local");
        User buyer = persistUser("buyer7@vintic.local");
        Auction auction = persistLiveAuction(persistProduct(seller), 100000L, 5000L);
        PurchaseGoal goal = persistActiveGoal(buyer, 500000L);

        PurchaseGoalEngagementResult engageResult = engagementTransactionService.engage(goal.getId(), auction.getId(), buyer.getId(), 200000L);
        assertThat(engageResult.engaged()).isTrue();

        PurchaseGoalCancelResponse cancelResponse = purchaseGoalCommandService.cancelGoal(goal.getId(), buyer.getId());

        assertThat(cancelResponse.status()).isEqualTo(PurchaseGoalStatus.CANCEL_REQUESTED);
        PurchaseGoal reloaded = purchaseGoalRepository.findById(goal.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PurchaseGoalStatus.CANCEL_REQUESTED);
        assertThat(reloaded.getCurrentAuctionId()).isEqualTo(auction.getId());
        // CANCEL_REQUESTED가 돼도 이미 만들어진 AutoBid는 이 취소가 건드리지 않는다 - 실제로
        // 입찰을 정리하는 것은 Day 6(경매 결과 관찰) 이후의 책임이다.
        assertThat(autoBidSettingRepository.findByAuctionIdAndUserIdAndActiveSlotTrue(auction.getId(), buyer.getId())).isPresent();
    }

    @Test
    void 취소가_먼저_커밋되면_참여는_실패하고_AutoBid를_만들지_않는다() {
        User seller = persistUser("seller8@vintic.local");
        User buyer = persistUser("buyer8@vintic.local");
        Auction auction = persistLiveAuction(persistProduct(seller), 100000L, 5000L);
        PurchaseGoal goal = persistActiveGoal(buyer, 500000L);

        PurchaseGoalCancelResponse cancelResponse = purchaseGoalCommandService.cancelGoal(goal.getId(), buyer.getId());
        assertThat(cancelResponse.status()).isEqualTo(PurchaseGoalStatus.CANCELLED);

        PurchaseGoalEngagementResult engageResult = engagementTransactionService.engage(goal.getId(), auction.getId(), buyer.getId(), 200000L);

        assertThat(engageResult.engaged()).isFalse();
        assertThat(purchaseGoalRepository.findById(goal.getId()).orElseThrow().getStatus()).isEqualTo(PurchaseGoalStatus.CANCELLED);
        assertThat(autoBidSettingRepository.findByAuctionIdAndUserIdAndActiveSlotTrue(auction.getId(), buyer.getId())).isEmpty();
    }

    // 취소와 참여를 실제로 동시에 실행해 락 순서(#Day5: goal row -> Auction -> AutoBidSetting)가
    // cancelGoal의 goal-row-only 경로와 부딪혀 데드락이 나지 않는지 실측한다. 두 결과 조합 모두
    // 유효하다 - 정확히 무엇이 이기는지는 스케줄링에 달려 있으므로 "깨지지 않는 결과인가"만 본다.
    @Test
    void 취소와_참여를_동시에_실행해도_상태가_깨지지_않는다() throws Exception {
        User seller = persistUser("seller9@vintic.local");
        User buyer = persistUser("buyer9@vintic.local");
        Auction auction = persistLiveAuction(persistProduct(seller), 100000L, 5000L);
        PurchaseGoal goal = persistActiveGoal(buyer, 500000L);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<PurchaseGoalEngagementResult> engageTask = () -> {
            ready.countDown();
            start.await();
            return engagementTransactionService.engage(goal.getId(), auction.getId(), buyer.getId(), 200000L);
        };
        Callable<PurchaseGoalCancelResponse> cancelTask = () -> {
            ready.countDown();
            start.await();
            return purchaseGoalCommandService.cancelGoal(goal.getId(), buyer.getId());
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<PurchaseGoalEngagementResult> engageFuture = executor.submit(engageTask);
            Future<PurchaseGoalCancelResponse> cancelFuture = executor.submit(cancelTask);
            ready.await();
            start.countDown();

            PurchaseGoalEngagementResult engageResult = engageFuture.get(30, TimeUnit.SECONDS);
            PurchaseGoalCancelResponse cancelResponse = cancelFuture.get(30, TimeUnit.SECONDS);

            PurchaseGoalStatus finalStatus = purchaseGoalRepository.findById(goal.getId()).orElseThrow().getStatus();
            assertThat(finalStatus).isIn(PurchaseGoalStatus.CANCELLED, PurchaseGoalStatus.CANCEL_REQUESTED);
            assertThat(cancelResponse.status()).isEqualTo(finalStatus);

            boolean autoBidExists = autoBidSettingRepository.findByAuctionIdAndUserIdAndActiveSlotTrue(auction.getId(), buyer.getId()).isPresent();
            if (finalStatus == PurchaseGoalStatus.CANCELLED) {
                assertThat(engageResult.engaged()).isFalse();
                assertThat(autoBidExists).isFalse();
            } else {
                assertThat(engageResult.engaged()).isTrue();
                assertThat(autoBidExists).isTrue();
            }
        } finally {
            executor.shutdown();
        }
    }
}
