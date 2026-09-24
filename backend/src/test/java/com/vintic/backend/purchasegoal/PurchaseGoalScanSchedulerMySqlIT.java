package com.vintic.backend.purchasegoal;

import com.vintic.backend.ai.purchase.dto.GoalCondition;
import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.autobid.repository.AutoBidSettingRepository;
import com.vintic.backend.notification.domain.NotificationType;
import com.vintic.backend.notification.repository.NotificationRepository;
import com.vintic.backend.order.domain.Order;
import com.vintic.backend.order.repository.OrderRepository;
import com.vintic.backend.product.domain.Product;
import com.vintic.backend.product.repository.ProductRepository;
import com.vintic.backend.purchasegoal.domain.PurchaseGoal;
import com.vintic.backend.purchasegoal.domain.PurchaseGoalStatus;
import com.vintic.backend.purchasegoal.repository.PurchaseGoalRepository;
import com.vintic.backend.purchasegoal.service.PurchaseGoalScanScheduler;
import com.vintic.backend.user.domain.User;
import com.vintic.backend.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
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

// Day 6: PurchaseGoalScanScheduler.scan()을 직접 호출해(cron 트리거를 기다리지 않는다,
// OrderExpirationConcurrencyMySqlIT와 동일한 관례) 3단계 순서·반복 스캔·동시 스캔만 실제 MySQL로
// 검증한다. deadline 경계 자체의 세부 조건, 낙찰/취소/CANCEL_REQUESTED 분기는 이미
// PurchaseGoalExpirationServiceTest/PurchaseGoalResultObservationServiceTest(H2)가 각 phase
// 단위로 충분히 본다 - 여기서는 "phase들이 한 tick 안에서 올바른 순서로 맞물리는가"와
// "여러 서버가 같은 tick을 동시에 돌려도 DB가 정확히 하나만 승인하는가"만 다룬다.
//
// purchase-agent.matcher.provider를 rule로 override한다 - 실제 OpenAI 호출 없이 결정적으로
// phase 3(Matcher 평가)까지 exercise하기 위해서다(OPENAI_API_KEY는 컨텍스트 기동에만 필요, 이
// 테스트에서 실제로 쓰이지 않는다).
//
// 실행: OPENAI_API_KEY=dummy ./gradlew test --tests "...PurchaseGoalScanSchedulerMySqlIT"
@SpringBootTest
@ActiveProfiles("local")
@Testcontainers
class PurchaseGoalScanSchedulerMySqlIT {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("purchase-agent.scan.enabled", () -> "true");
        registry.add("purchase-agent.matcher.provider", () -> "rule");
        registry.add("auction.lifecycle.start.enabled", () -> "false");
        registry.add("auction.lifecycle.end.enabled", () -> "false");
        registry.add("payment.expiration.enabled", () -> "false");
        registry.add("backup-offer.expiration.enabled", () -> "false");
    }

    @Autowired
    private PurchaseGoalScanScheduler scanScheduler;

    @Autowired
    private PurchaseGoalRepository purchaseGoalRepository;

    @Autowired
    private AuctionRepository auctionRepository;

    @Autowired
    private AutoBidSettingRepository autoBidSettingRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private UserRepository userRepository;

    private User persistUser(String email) {
        return userRepository.save(User.register(email, email, null));
    }

    // 실제 PricingService가 시세를 낼 수 있는 실데이터(used_market_prices.csv)를 그대로 쓴다 -
    // Day 3/4/5 테스트들과 동일한 방식이다. @SpringBootTest는 클래스 안 테스트끼리 DB를 공유하고
    // 롤백하지 않으므로(@DataJpaTest와 다름), Day 3 Finder의 후보 조회가 브랜드/모델로 SQL 단계에서
    // 거르지 않는다는 점과 맞물려 서로 다른 테스트가 같은 브랜드/모델을 쓰면 한 테스트의 경매가
    // 다른 테스트의 후보 풀에 섞여 들어갈 수 있다 - 그래서 goal.modelKey로 실제 후보를 가려내는
    // 테스트(패배 후 재탐색/동시 스캔)마다 서로 다른 카탈로그 모델을 쓴다.
    private Product persistProduct(User seller, String brand, String model) {
        return productRepository.save(new Product(
                seller, List.of("https://example.com/a.jpg"),
                brand, model, "Grey", 270, "A", "PARTIAL",
                300000, 350000, "", 290000, "", ""
        ));
    }

    // startPrice/bidIncrement를 낮게 잡아 minNextBidAmount가 실제 산출 시세보다 한참 낮게 유지되도록
    // 한다 - cap 미달로 참여가 막히지 않게 하기 위해서다.
    private Auction persistLiveAuction(Product product, LocalDateTime startAt, LocalDateTime endAt) {
        Auction auction = Auction.schedule(product, 1000L, 500L, startAt, endAt);
        auction.start();
        return auctionRepository.save(auction);
    }

    private Auction persistEndedAuction(Product product) {
        Auction auction = Auction.schedule(product, 1000L, 500L, LocalDateTime.now().minusHours(2), LocalDateTime.now().minusHours(1));
        auction.start();
        auction.end();
        return auctionRepository.save(auction);
    }

    private void persistOrder(Auction auction, User buyer) {
        orderRepository.save(Order.createForWinner(auction, buyer, 150000L, 3000L, LocalDateTime.now().plusDays(1)));
    }

    private PurchaseGoal persistActiveGoal(User owner, LocalDateTime deadline) {
        return persistActiveGoal(owner, deadline, "New Balance", "nb990", "뉴발란스 990");
    }

    private PurchaseGoal persistActiveGoal(User owner, LocalDateTime deadline, String brand, String modelKey, String modelQuery) {
        PurchaseGoal goal = PurchaseGoal.create(
                owner, brand, modelKey, modelQuery,
                GoalCondition.B, null, 200000L, null, deadline, LocalDateTime.now()
        );
        return purchaseGoalRepository.save(goal);
    }

    @Test
    void deadline이_지난_ACTIVE_Goal은_같은_tick에서_만료되고_참여를_시도하지_않는다() {
        User buyer = persistUser("buyer1@vintic.local");
        PurchaseGoal goal = persistActiveGoal(buyer, LocalDateTime.now().minusMinutes(1));

        scanScheduler.scan();

        PurchaseGoal reloaded = purchaseGoalRepository.findById(goal.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PurchaseGoalStatus.EXPIRED);
        assertThat(autoBidSettingRepository.findAll().stream()
                .noneMatch(s -> s.getPurchaseGoalId() != null && s.getPurchaseGoalId().equals(goal.getId())))
                .isTrue();
    }

    @Test
    void 참여중인_Goal은_deadline이_지나도_경매가_끝날_때까지_기다리다가_패배하면_EXPIRED로_끝난다() {
        User seller = persistUser("seller2@vintic.local");
        User buyer = persistUser("buyer2@vintic.local");
        Product product = persistProduct(seller, "New Balance", "990v6");
        Auction auction = persistLiveAuction(product, LocalDateTime.now().minusMinutes(30), LocalDateTime.now().plusHours(1));
        PurchaseGoal goal = persistActiveGoal(buyer, LocalDateTime.now().minusMinutes(1)); // deadline 이미 지남
        // Day 5 없이 ENGAGED 상태를 직접 만든다 - phase 2/1의 상호작용만 보는 테스트라 phase 3를
        // 거칠 필요가 없다(같은 이유로 AutoBidCommandServiceTest류가 이미 ReflectionTestUtils로
        // 상태를 직접 맞추는 관례를 그대로 쓴다는 뜻은 아니다 - 여기서는 실제 Day 5 트랜잭션 서비스를
        // 쓰지 않고 currentAuctionId만 채운 뒤 저장한다).
        ReflectionTestUtils.setField(goal, "status", PurchaseGoalStatus.ENGAGED);
        ReflectionTestUtils.setField(goal, "currentAuctionId", auction.getId());
        purchaseGoalRepository.save(goal);

        scanScheduler.scan();
        assertThat(purchaseGoalRepository.findById(goal.getId()).orElseThrow().getStatus())
                .as("경매가 아직 LIVE이므로 deadline이 지났어도 EXPIRED가 아니라 ENGAGED로 남아야 한다")
                .isEqualTo(PurchaseGoalStatus.ENGAGED);

        Auction reloadedAuction = auctionRepository.findById(auction.getId()).orElseThrow();
        reloadedAuction.end();
        auctionRepository.save(reloadedAuction);

        scanScheduler.scan();

        assertThat(purchaseGoalRepository.findById(goal.getId()).orElseThrow().getStatus()).isEqualTo(PurchaseGoalStatus.EXPIRED);
    }

    @Test
    void 패배_후_ACTIVE로_복귀한_Goal은_같은_스캔에서_새_경매를_찾아_다시_참여한다() {
        User seller = persistUser("seller3@vintic.local");
        User buyer = persistUser("buyer3@vintic.local");
        Auction lostAuction = persistEndedAuction(persistProduct(seller, "Nike", "Jordan 1"));
        Auction newAuction = persistLiveAuction(
                persistProduct(seller, "Nike", "Jordan 1"), LocalDateTime.now().minusMinutes(10), LocalDateTime.now().plusHours(2)
        );
        PurchaseGoal goal = persistActiveGoal(buyer, LocalDateTime.now().plusDays(1), "Nike", "jordan1", "나이키 조던 1");
        ReflectionTestUtils.setField(goal, "status", PurchaseGoalStatus.ENGAGED);
        ReflectionTestUtils.setField(goal, "currentAuctionId", lostAuction.getId());
        purchaseGoalRepository.save(goal);

        scanScheduler.scan();

        PurchaseGoal reloaded = purchaseGoalRepository.findById(goal.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PurchaseGoalStatus.ENGAGED);
        assertThat(reloaded.getCurrentAuctionId()).isEqualTo(newAuction.getId());
        assertThat(autoBidSettingRepository.findByAuctionIdAndUserIdAndActiveSlotTrue(newAuction.getId(), buyer.getId()))
                .hasValueSatisfying(setting -> assertThat(setting.getPurchaseGoalId()).isEqualTo(goal.getId()));
    }

    @Test
    void 반복_스캔은_같은_Goal을_다시_참여시키지_않는다() {
        User seller = persistUser("seller4@vintic.local");
        User buyer = persistUser("buyer4@vintic.local");
        Auction auction = persistLiveAuction(
                persistProduct(seller, "New Balance", "990v6"), LocalDateTime.now().minusMinutes(10), LocalDateTime.now().plusHours(2)
        );
        PurchaseGoal goal = persistActiveGoal(buyer, LocalDateTime.now().plusDays(1));

        scanScheduler.scan();
        PurchaseGoal afterFirst = purchaseGoalRepository.findById(goal.getId()).orElseThrow();
        assertThat(afterFirst.getStatus()).isEqualTo(PurchaseGoalStatus.ENGAGED);
        assertThat(afterFirst.getCurrentAuctionId()).isEqualTo(auction.getId());

        scanScheduler.scan();
        scanScheduler.scan();

        PurchaseGoal afterRepeat = purchaseGoalRepository.findById(goal.getId()).orElseThrow();
        assertThat(afterRepeat.getStatus()).isEqualTo(PurchaseGoalStatus.ENGAGED);
        assertThat(afterRepeat.getCurrentAuctionId()).isEqualTo(auction.getId());
        long rowsForThisGoal = autoBidSettingRepository.findAll().stream()
                .filter(s -> goal.getId().equals(s.getPurchaseGoalId()))
                .count();
        assertThat(rowsForThisGoal).isEqualTo(1);
    }

    @Test
    void 동시_스캔이_실행돼도_같은_Goal은_정확히_한_번만_참여한다() throws Exception {
        User seller = persistUser("seller5@vintic.local");
        User buyer = persistUser("buyer5@vintic.local");
        Auction auction = persistLiveAuction(
                persistProduct(seller, "Vans", "Old Skool"), LocalDateTime.now().minusMinutes(10), LocalDateTime.now().plusHours(2)
        );
        PurchaseGoal goal = persistActiveGoal(buyer, LocalDateTime.now().plusDays(1), "Vans", "oldskool", "반스 올드스쿨");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Void> task = () -> {
            ready.countDown();
            start.await();
            scanScheduler.scan();
            return null;
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Void> future1 = executor.submit(task);
            Future<Void> future2 = executor.submit(task);
            ready.await();
            start.countDown();

            future1.get(60, TimeUnit.SECONDS);
            future2.get(60, TimeUnit.SECONDS);

            PurchaseGoal reloaded = purchaseGoalRepository.findById(goal.getId()).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(PurchaseGoalStatus.ENGAGED);
            assertThat(reloaded.getCurrentAuctionId()).isEqualTo(auction.getId());

            long rowsForThisGoal = autoBidSettingRepository.findAll().stream()
                    .filter(s -> goal.getId().equals(s.getPurchaseGoalId()))
                    .count();
            assertThat(rowsForThisGoal).isEqualTo(1);

            // 반복·동시 스캔으로 중복 알림이 생기면 안 된다(#Day7) - 실제 동시 실행에서도
            // PURCHASE_AGENT_ENGAGED가 정확히 1건이어야 한다.
            long engagedNotificationsForThisGoal = notificationRepository.findAll().stream()
                    .filter(n -> n.getType() == NotificationType.PURCHASE_AGENT_ENGAGED)
                    .filter(n -> ("PURCHASE_AGENT_ENGAGED:" + goal.getId() + ":" + auction.getId()).equals(n.getBusinessEventKey()))
                    .count();
            assertThat(engagedNotificationsForThisGoal).isEqualTo(1);
        } finally {
            executor.shutdown();
        }
    }
}
