package com.vintic.backend.purchasegoal.service;

import com.vintic.backend.ai.purchase.dto.GoalCondition;
import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.config.ClockConfig;
import com.vintic.backend.notification.domain.Notification;
import com.vintic.backend.notification.domain.NotificationType;
import com.vintic.backend.notification.repository.NotificationRepository;
import com.vintic.backend.notification.service.NotificationRecorder;
import com.vintic.backend.order.domain.Order;
import com.vintic.backend.order.repository.OrderRepository;
import com.vintic.backend.order.service.AuctionSettlementService;
import com.vintic.backend.product.domain.Product;
import com.vintic.backend.purchasegoal.domain.PurchaseGoal;
import com.vintic.backend.purchasegoal.domain.PurchaseGoalStatus;
import com.vintic.backend.purchasegoal.repository.PurchaseGoalRepository;
import com.vintic.backend.support.TestClockConfig;
import com.vintic.backend.user.domain.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// Day 6 phase 2(결과 관찰) + Day 7 PURCHASE_AGENT_LOST/기존 AUCTION_WON 재사용을 함께 검증한다.
// phase 1(만료)/phase 3(탐색·참여)는 각자 다른 테스트에서 본다.
@DataJpaTest
@Import({
        PurchaseGoalResultObservationService.class, NotificationRecorder.class,
        AuctionSettlementService.class, TestClockConfig.class
})
class PurchaseGoalResultObservationServiceTest {

    private static final LocalDateTime FIXED_NOW = LocalDateTime.ofInstant(TestClockConfig.FIXED_INSTANT, ClockConfig.APP_ZONE);

    @Autowired
    private PurchaseGoalResultObservationService resultObservationService;

    @Autowired
    private PurchaseGoalRepository purchaseGoalRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private AuctionSettlementService auctionSettlementService;

    @Autowired
    private EntityManager entityManager;

    private User persistUser(String email) {
        User user = User.register(email, email, null);
        entityManager.persist(user);
        return user;
    }

    private Product persistProduct(User seller) {
        Product product = new Product(
                seller, List.of("https://example.com/a.jpg"),
                "New Balance", "990v6", "Grey", 270, "A", "PARTIAL",
                300000, 350000, "", 290000, "", ""
        );
        entityManager.persist(product);
        return product;
    }

    private Auction persistLiveAuction(Product product) {
        Auction auction = Auction.schedule(product, 100000L, 5000L, FIXED_NOW.minusHours(2), FIXED_NOW.minusHours(1));
        auction.start();
        entityManager.persist(auction);
        return auction;
    }

    private Auction persistEndedAuction(Product product) {
        Auction auction = Auction.schedule(product, 100000L, 5000L, FIXED_NOW.minusHours(2), FIXED_NOW.minusHours(1));
        auction.start();
        auction.end();
        entityManager.persist(auction);
        return auction;
    }

    private Auction persistCanceledAuction(Product product) {
        Auction auction = Auction.schedule(product, 100000L, 5000L, FIXED_NOW.plusHours(1), FIXED_NOW.plusHours(2));
        auction.cancel();
        entityManager.persist(auction);
        return auction;
    }

    private void persistOrder(Auction auction, User buyer) {
        Order order = Order.createForWinner(auction, buyer, 150000L, 3000L, FIXED_NOW.plusDays(1));
        entityManager.persist(order);
    }

    private PurchaseGoal persistGoal(User owner, PurchaseGoalStatus status, Long currentAuctionId, LocalDateTime deadline) {
        PurchaseGoal goal = PurchaseGoal.create(
                owner, "New Balance", "nb990", "뉴발란스 990",
                GoalCondition.B, null, 200000L, null, deadline, FIXED_NOW.minusDays(1)
        );
        ReflectionTestUtils.setField(goal, "status", status);
        ReflectionTestUtils.setField(goal, "currentAuctionId", currentAuctionId);
        return purchaseGoalRepository.saveAndFlush(goal);
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void 경매가_아직_진행중이면_기다리고_상태를_바꾸지_않는다() {
        User seller = persistUser("seller1@vintic.local");
        User buyer = persistUser("buyer1@vintic.local");
        Auction auction = persistLiveAuction(persistProduct(seller));
        PurchaseGoal goal = persistGoal(buyer, PurchaseGoalStatus.ENGAGED, auction.getId(), FIXED_NOW.plusDays(1));
        flushAndClear();

        resultObservationService.observeIfDue(goal.getId());

        PurchaseGoal reloaded = purchaseGoalRepository.findById(goal.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PurchaseGoalStatus.ENGAGED);
        assertThat(reloaded.getCurrentAuctionId()).isEqualTo(auction.getId());
    }

    @Test
    void 낙찰한_ENGAGED_Goal은_FULFILLED로_전이하고_currentAuctionId를_유지한다() {
        User seller = persistUser("seller2@vintic.local");
        User buyer = persistUser("buyer2@vintic.local");
        Auction auction = persistEndedAuction(persistProduct(seller));
        persistOrder(auction, buyer);
        PurchaseGoal goal = persistGoal(buyer, PurchaseGoalStatus.ENGAGED, auction.getId(), FIXED_NOW.plusDays(1));
        flushAndClear();

        resultObservationService.observeIfDue(goal.getId());

        PurchaseGoal reloaded = purchaseGoalRepository.findById(goal.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PurchaseGoalStatus.FULFILLED);
        assertThat(reloaded.getCurrentAuctionId()).isEqualTo(auction.getId());
    }

    @Test
    void 패배한_ENGAGED_Goal은_deadline_전이면_ACTIVE로_복귀하고_currentAuctionId를_비운다() {
        User seller = persistUser("seller3@vintic.local");
        User buyer = persistUser("buyer3@vintic.local");
        Auction auction = persistEndedAuction(persistProduct(seller));
        PurchaseGoal goal = persistGoal(buyer, PurchaseGoalStatus.ENGAGED, auction.getId(), FIXED_NOW.plusDays(1));
        flushAndClear();

        resultObservationService.observeIfDue(goal.getId());

        PurchaseGoal reloaded = purchaseGoalRepository.findById(goal.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PurchaseGoalStatus.ACTIVE);
        assertThat(reloaded.getCurrentAuctionId()).isNull();

        List<Notification> notifications = notificationRepository.findAll();
        assertThat(notifications).hasSize(1);
        Notification notification = notifications.get(0);
        assertThat(notification.getType()).isEqualTo(NotificationType.PURCHASE_AGENT_LOST);
        assertThat(notification.getBusinessEventKey()).isEqualTo("PURCHASE_AGENT_LOST:" + goal.getId() + ":" + auction.getId());
        // ACTIVE로 복귀(계속 탐색) 문구여야 한다 - "종료" 문구가 아니다.
        assertThat(notification.getBody()).contains("찾고 있습니다");
    }

    @Test
    void 패배한_ENGAGED_Goal은_deadline_후면_EXPIRED로_끝난다() {
        User seller = persistUser("seller4@vintic.local");
        User buyer = persistUser("buyer4@vintic.local");
        Auction auction = persistEndedAuction(persistProduct(seller));
        PurchaseGoal goal = persistGoal(buyer, PurchaseGoalStatus.ENGAGED, auction.getId(), FIXED_NOW.minusMinutes(1));
        flushAndClear();

        resultObservationService.observeIfDue(goal.getId());

        assertThat(purchaseGoalRepository.findById(goal.getId()).orElseThrow().getStatus()).isEqualTo(PurchaseGoalStatus.EXPIRED);

        Notification notification = notificationRepository.findAll().get(0);
        assertThat(notification.getBusinessEventKey()).isEqualTo("PURCHASE_AGENT_LOST:" + goal.getId() + ":" + auction.getId());
        // 종료(더 이상 참여 불가) 문구여야 한다 - "계속 찾는다" 문구와 달라야 한다.
        assertThat(notification.getBody()).contains("종료합니다");
    }

    @Test
    void 경매가_취소되면_패배와_동일하게_처리한다() {
        User seller = persistUser("seller5@vintic.local");
        User buyer = persistUser("buyer5@vintic.local");
        Auction auction = persistCanceledAuction(persistProduct(seller));
        PurchaseGoal goal = persistGoal(buyer, PurchaseGoalStatus.ENGAGED, auction.getId(), FIXED_NOW.plusDays(1));
        flushAndClear();

        resultObservationService.observeIfDue(goal.getId());

        PurchaseGoal reloaded = purchaseGoalRepository.findById(goal.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PurchaseGoalStatus.ACTIVE);
        assertThat(reloaded.getCurrentAuctionId()).isNull();
    }

    @Test
    void 낙찰한_CANCEL_REQUESTED_Goal은_FULFILLED로_전이한다() {
        User seller = persistUser("seller6@vintic.local");
        User buyer = persistUser("buyer6@vintic.local");
        Auction auction = persistEndedAuction(persistProduct(seller));
        persistOrder(auction, buyer);
        PurchaseGoal goal = persistGoal(buyer, PurchaseGoalStatus.CANCEL_REQUESTED, auction.getId(), FIXED_NOW.plusDays(1));
        flushAndClear();

        resultObservationService.observeIfDue(goal.getId());

        assertThat(purchaseGoalRepository.findById(goal.getId()).orElseThrow().getStatus()).isEqualTo(PurchaseGoalStatus.FULFILLED);
    }

    @Test
    void 패배한_CANCEL_REQUESTED_Goal은_deadline과_무관하게_CANCELLED로_전이한다() {
        User seller = persistUser("seller7@vintic.local");
        User buyer = persistUser("buyer7@vintic.local");
        Auction auction = persistEndedAuction(persistProduct(seller));
        // deadline이 이미 지났어도 CANCEL_REQUESTED는 EXPIRED가 아니라 CANCELLED로 끝나야 한다.
        PurchaseGoal goal = persistGoal(buyer, PurchaseGoalStatus.CANCEL_REQUESTED, auction.getId(), FIXED_NOW.minusMinutes(1));
        flushAndClear();

        resultObservationService.observeIfDue(goal.getId());

        assertThat(purchaseGoalRepository.findById(goal.getId()).orElseThrow().getStatus()).isEqualTo(PurchaseGoalStatus.CANCELLED);
        Notification notification = notificationRepository.findAll().get(0);
        assertThat(notification.getType()).isEqualTo(NotificationType.PURCHASE_AGENT_LOST);
        assertThat(notification.getBusinessEventKey()).isEqualTo("PURCHASE_AGENT_LOST:" + goal.getId() + ":" + auction.getId());
    }

    @Test
    void 다른_사용자의_Order는_내_낙찰로_인정하지_않는다() {
        User seller = persistUser("seller8@vintic.local");
        User buyer = persistUser("buyer8@vintic.local");
        User otherBuyer = persistUser("other8@vintic.local");
        Auction auction = persistEndedAuction(persistProduct(seller));
        persistOrder(auction, otherBuyer);
        PurchaseGoal goal = persistGoal(buyer, PurchaseGoalStatus.ENGAGED, auction.getId(), FIXED_NOW.plusDays(1));
        flushAndClear();

        resultObservationService.observeIfDue(goal.getId());

        PurchaseGoal reloaded = purchaseGoalRepository.findById(goal.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PurchaseGoalStatus.ACTIVE);
    }

    @Test
    void 이미_처리된_Goal을_다시_관찰해도_안전하다() {
        User seller = persistUser("seller9@vintic.local");
        User buyer = persistUser("buyer9@vintic.local");
        Auction auction = persistEndedAuction(persistProduct(seller));
        PurchaseGoal goal = persistGoal(buyer, PurchaseGoalStatus.ENGAGED, auction.getId(), FIXED_NOW.plusDays(1));
        flushAndClear();

        resultObservationService.observeIfDue(goal.getId());
        PurchaseGoal afterFirst = purchaseGoalRepository.findById(goal.getId()).orElseThrow();
        assertThat(afterFirst.getStatus()).isEqualTo(PurchaseGoalStatus.ACTIVE);

        // 같은 goal을 다시 관찰(중복 관찰, 늦게 도착한 스캔 등) - 이미 ACTIVE라 그대로 안전하게 지나간다.
        resultObservationService.observeIfDue(goal.getId());

        PurchaseGoal afterSecond = purchaseGoalRepository.findById(goal.getId()).orElseThrow();
        assertThat(afterSecond.getStatus()).isEqualTo(PurchaseGoalStatus.ACTIVE);
        assertThat(afterSecond.getCurrentAuctionId()).isNull();
        // 첫 호출에서 만든 PURCHASE_AGENT_LOST 1건만 있어야 한다 - 중복 관찰이 새 알림을 만들면 안 된다.
        assertThat(notificationRepository.findAll()).hasSize(1);
    }

    @Test
    void 낙찰하면_기존_AUCTION_WON_알림만_있고_새_알림을_만들지_않는다() {
        User seller = persistUser("seller10@vintic.local");
        User buyer = persistUser("buyer10@vintic.local");
        Auction auction = persistEndedAuction(persistProduct(seller));
        // 실제 정산 경로(#56-1)를 그대로 태워 AUCTION_WON을 먼저 만든다 - Day 6이 이걸 대체하거나
        // 중복시키지 않는지 확인하는 게 이 테스트의 목적이다.
        ReflectionTestUtils.setField(auction, "currentWinner", buyer);
        auctionSettlementService.settle(auction.getId());
        PurchaseGoal goal = persistGoal(buyer, PurchaseGoalStatus.ENGAGED, auction.getId(), FIXED_NOW.plusDays(1));
        flushAndClear();

        resultObservationService.observeIfDue(goal.getId());

        assertThat(purchaseGoalRepository.findById(goal.getId()).orElseThrow().getStatus()).isEqualTo(PurchaseGoalStatus.FULFILLED);
        List<Notification> notifications = notificationRepository.findAll();
        assertThat(notifications).hasSize(1);
        assertThat(notifications.get(0).getType()).isEqualTo(NotificationType.AUCTION_WON);
    }

    @Test
    void 서로_다른_경매에서_두_번_패배하면_각각_다른_키로_LOST_알림이_남는다() {
        User seller = persistUser("seller11@vintic.local");
        User buyer = persistUser("buyer11@vintic.local");
        Auction auctionA = persistEndedAuction(persistProduct(seller));
        Auction auctionB = persistEndedAuction(persistProduct(seller));
        PurchaseGoal goal = persistGoal(buyer, PurchaseGoalStatus.ENGAGED, auctionA.getId(), FIXED_NOW.plusDays(1));
        flushAndClear();

        resultObservationService.observeIfDue(goal.getId());
        PurchaseGoal afterFirstLoss = purchaseGoalRepository.findById(goal.getId()).orElseThrow();
        assertThat(afterFirstLoss.getStatus()).isEqualTo(PurchaseGoalStatus.ACTIVE);

        // Day 5 engage()를 거치지 않고 두 번째 참여 상태만 재현한다(observeIfDue만 보는 테스트다).
        ReflectionTestUtils.setField(afterFirstLoss, "status", PurchaseGoalStatus.ENGAGED);
        ReflectionTestUtils.setField(afterFirstLoss, "currentAuctionId", auctionB.getId());
        purchaseGoalRepository.saveAndFlush(afterFirstLoss);
        entityManager.clear();

        resultObservationService.observeIfDue(goal.getId());

        List<Notification> lostNotifications = notificationRepository.findAll().stream()
                .filter(n -> n.getType() == NotificationType.PURCHASE_AGENT_LOST)
                .toList();
        assertThat(lostNotifications).hasSize(2);
        assertThat(lostNotifications).extracting(Notification::getBusinessEventKey)
                .containsExactlyInAnyOrder(
                        "PURCHASE_AGENT_LOST:" + goal.getId() + ":" + auctionA.getId(),
                        "PURCHASE_AGENT_LOST:" + goal.getId() + ":" + auctionB.getId()
                );
    }
}
