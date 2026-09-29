package com.vintic.backend.purchasegoal.service;

import com.vintic.backend.ai.purchase.dto.GoalCondition;
import com.vintic.backend.auction.audit.AuctionPriceAuditRecorder;
import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.autobid.domain.AutoBidSetting;
import com.vintic.backend.autobid.proxy.ProxyPriceEngine;
import com.vintic.backend.autobid.repository.AutoBidSettingRepository;
import com.vintic.backend.autobid.service.AutoBidCommandService;
import com.vintic.backend.common.exception.AutoBidAlreadyExistsException;
import com.vintic.backend.notification.domain.Notification;
import com.vintic.backend.notification.domain.NotificationType;
import com.vintic.backend.notification.repository.NotificationRepository;
import com.vintic.backend.notification.service.NotificationRecorder;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Day 7: 참여 성공/실패/중복 시도에 따른 PURCHASE_AGENT_ENGAGED 알림 생성 여부만 검증한다 -
// AutoBidCommandServiceTest와 동일한 @Import 관례(필요한 협력자를 그대로 @DataJpaTest에 얹는다)를
// 쓴다. 실패 시 "알림이 생성되지 않는다"는 notificationRecorder 호출이 createAutoBid() 성공 *이후*
// 코드 순서에 있어 예외 발생 시 아예 도달하지 않는다는 사실만으로 같은 트랜잭션 안에서도 검증
// 가능하다(실제 commit/rollback 경계까지는 필요 없다) - 물리 트랜잭션 원자성 자체는 Day 5의
// PurchaseGoalEngagementMySqlIT가 이미 실제 MySQL로 증명했다.
@DataJpaTest
@Import({
        PurchaseGoalEngagementTransactionService.class, AutoBidCommandService.class, ProxyPriceEngine.class,
        AuctionPriceAuditRecorder.class, AgentManagedAuctionGuard.class, NotificationRecorder.class, TestClockConfig.class
})
class PurchaseGoalEngagementTransactionServiceTest {

    @Autowired
    private PurchaseGoalEngagementTransactionService engagementTransactionService;

    @Autowired
    private PurchaseGoalRepository purchaseGoalRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private AutoBidSettingRepository autoBidSettingRepository;

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
        Auction auction = Auction.schedule(product, 100000L, 5000L, LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(1));
        auction.start();
        entityManager.persist(auction);
        return auction;
    }

    private PurchaseGoal persistActiveGoal(User owner) {
        LocalDateTime now = LocalDateTime.now();
        PurchaseGoal goal = PurchaseGoal.create(
                owner, "New Balance", "nb990", "뉴발란스 990",
                GoalCondition.B, null, 200000L, null, now.plusDays(7), now
        );
        return purchaseGoalRepository.saveAndFlush(goal);
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void 참여에_성공하면_PURCHASE_AGENT_ENGAGED_알림이_생성된다() {
        User seller = persistUser("seller1@vintic.local");
        User buyer = persistUser("buyer1@vintic.local");
        Auction auction = persistLiveAuction(persistProduct(seller));
        PurchaseGoal goal = persistActiveGoal(buyer);
        flushAndClear();

        PurchaseGoalEngagementResult result = engagementTransactionService.engage(goal.getId(), auction.getId(), buyer.getId(), 200000L);

        assertThat(result.engaged()).isTrue();
        List<Notification> notifications = notificationRepository.findAll();
        assertThat(notifications).hasSize(1);
        Notification notification = notifications.get(0);
        assertThat(notification.getType()).isEqualTo(NotificationType.PURCHASE_AGENT_ENGAGED);
        assertThat(notification.getBusinessEventKey()).isEqualTo("PURCHASE_AGENT_ENGAGED:" + goal.getId() + ":" + auction.getId());
        assertThat(notification.getRecipient().getId()).isEqualTo(buyer.getId());
        assertThat(notification.getAuctionId()).isEqualTo(auction.getId());
        assertThat(notification.getResourceId()).isEqualTo(goal.getId());
    }

    @Test
    void AutoBid_등록이_실패하면_알림도_생성되지_않는다() {
        User seller = persistUser("seller2@vintic.local");
        User buyer = persistUser("buyer2@vintic.local");
        Auction auction = persistLiveAuction(persistProduct(seller));
        // 이미 등록된 AutoBid가 있어 createAutoBid()가 AutoBidAlreadyExistsException을 던지게 만든다.
        entityManager.persist(AutoBidSetting.reserve(auction, buyer, 150000L));
        PurchaseGoal goal = persistActiveGoal(buyer);
        flushAndClear();

        assertThatThrownBy(() -> engagementTransactionService.engage(goal.getId(), auction.getId(), buyer.getId(), 200000L))
                .isInstanceOf(AutoBidAlreadyExistsException.class);

        assertThat(notificationRepository.findAll()).isEmpty();
    }

    @Test
    void 이미_참여중인_Goal에_다시_engage해도_알림이_중복되지_않는다() {
        User seller = persistUser("seller3@vintic.local");
        User buyer = persistUser("buyer3@vintic.local");
        Auction auction = persistLiveAuction(persistProduct(seller));
        PurchaseGoal goal = persistActiveGoal(buyer);
        flushAndClear();

        PurchaseGoalEngagementResult first = engagementTransactionService.engage(goal.getId(), auction.getId(), buyer.getId(), 200000L);
        assertThat(first.engaged()).isTrue();

        // 같은 goal은 이제 ENGAGED다 - transitionToEngaged가 0을 반환하고 조기 반환한다(중복
        // AutoBid 시도조차 하지 않는다).
        PurchaseGoalEngagementResult second = engagementTransactionService.engage(goal.getId(), auction.getId(), buyer.getId(), 200000L);

        assertThat(second.engaged()).isFalse();
        assertThat(notificationRepository.findAll()).hasSize(1);
    }

    @Test
    void 서로_다른_경매_참여는_각각_다른_키로_알림을_남긴다() {
        User seller = persistUser("seller4@vintic.local");
        User buyer = persistUser("buyer4@vintic.local");
        Auction auctionA = persistLiveAuction(persistProduct(seller));
        Auction auctionB = persistLiveAuction(persistProduct(seller));
        PurchaseGoal goal = persistActiveGoal(buyer);
        flushAndClear();

        engagementTransactionService.engage(goal.getId(), auctionA.getId(), buyer.getId(), 200000L);

        // auctionA 낙찰 실패로 ACTIVE 복귀를 흉내낸다(Day 6이 실제로 하는 일 - 여기서는 다음
        // engage()를 부를 수 있는 상태만 재현하면 된다).
        PurchaseGoal reloaded = purchaseGoalRepository.findById(goal.getId()).orElseThrow();
        ReflectionTestUtils.setField(reloaded, "status", PurchaseGoalStatus.ACTIVE);
        purchaseGoalRepository.saveAndFlush(reloaded);
        entityManager.clear();

        engagementTransactionService.engage(goal.getId(), auctionB.getId(), buyer.getId(), 200000L);

        List<Notification> notifications = notificationRepository.findAll();
        assertThat(notifications).hasSize(2);
        assertThat(notifications).extracting(Notification::getBusinessEventKey)
                .containsExactlyInAnyOrder(
                        "PURCHASE_AGENT_ENGAGED:" + goal.getId() + ":" + auctionA.getId(),
                        "PURCHASE_AGENT_ENGAGED:" + goal.getId() + ":" + auctionB.getId()
                );
    }
}
