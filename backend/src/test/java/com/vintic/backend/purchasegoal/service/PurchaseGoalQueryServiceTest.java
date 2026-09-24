package com.vintic.backend.purchasegoal.service;

import com.vintic.backend.ai.purchase.dto.GoalCondition;
import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.autobid.domain.AutoBidSetting;
import com.vintic.backend.common.exception.PurchaseGoalAccessDeniedException;
import com.vintic.backend.order.domain.Order;
import com.vintic.backend.order.domain.OrderStatus;
import com.vintic.backend.product.domain.Product;
import com.vintic.backend.purchasegoal.domain.PurchaseGoal;
import com.vintic.backend.purchasegoal.domain.PurchaseGoalMatch;
import com.vintic.backend.purchasegoal.dto.PurchaseGoalDetailResponse;
import com.vintic.backend.purchasegoal.dto.PurchaseGoalMatchHistoryResponse;
import com.vintic.backend.purchasegoal.dto.PurchaseGoalParticipationResponse;
import com.vintic.backend.purchasegoal.dto.PurchaseGoalResponse;
import com.vintic.backend.purchasegoal.repository.PurchaseGoalRepository;
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
import static org.assertj.core.api.Assertions.tuple;

// OrderQueryService의 "404 -> 403 순서" 관례를 그대로 검증한다(Day2 A: 본인 조회/타인 접근 차단).
@DataJpaTest
@Import(PurchaseGoalQueryService.class)
class PurchaseGoalQueryServiceTest {

    @Autowired
    private PurchaseGoalQueryService purchaseGoalQueryService;

    @Autowired
    private PurchaseGoalRepository purchaseGoalRepository;

    @Autowired
    private EntityManager entityManager;

    private User persistUser(String email) {
        User user = User.register(email, email, null);
        entityManager.persist(user);
        return user;
    }

    private PurchaseGoal persistGoal(User owner) {
        LocalDateTime now = LocalDateTime.now();
        PurchaseGoal goal = PurchaseGoal.create(
                owner, "New Balance", "nb990", "뉴발란스 990",
                GoalCondition.A, 270, 150000L,
                null, now.plusDays(7), now
        );
        return purchaseGoalRepository.saveAndFlush(goal);
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

    private Auction persistScheduledAuction(Product product) {
        Auction auction = Auction.schedule(product, 100000L, 5000L, LocalDateTime.now().plusHours(1), LocalDateTime.now().plusHours(2));
        entityManager.persist(auction);
        return auction;
    }

    private Auction persistLiveAuction(Product product) {
        Auction auction = Auction.schedule(product, 100000L, 5000L, LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(1));
        auction.start();
        entityManager.persist(auction);
        return auction;
    }

    private Auction persistEndedAuction(Product product) {
        Auction auction = Auction.schedule(product, 100000L, 5000L, LocalDateTime.now().minusHours(2), LocalDateTime.now().minusHours(1));
        auction.start();
        auction.end();
        entityManager.persist(auction);
        return auction;
    }

    private void persistAutoBidSetting(Auction auction, User buyer, Long goalId) {
        entityManager.persist(AutoBidSetting.reserve(auction, buyer, 200000L, goalId));
    }

    private void persistOrder(Auction auction, User buyer) {
        entityManager.persist(Order.createForWinner(auction, buyer, 150000L, 3000L, LocalDateTime.now().plusDays(1)));
    }

    // 결제 완료 여부와 무관하게 "낙찰=Order 존재"임을 확인하기 위해 상태를 강제로 바꿀 때 쓴다.
    private void persistOrderWithStatus(Auction auction, User buyer, OrderStatus status) {
        Order order = Order.createForWinner(auction, buyer, 150000L, 3000L, LocalDateTime.now().plusDays(1));
        ReflectionTestUtils.setField(order, "status", status);
        entityManager.persist(order);
    }

    // 수동 BackupOffer 수락 경로(#56-3)로 생긴 Order를 흉내낸다 - 원래 낙찰자가 아니어도 같은
    // auction에 본인 Order가 남을 수 있다는 것을 보여주기 위함이다.
    private void persistBackupAcceptOrder(Auction auction, User buyer) {
        entityManager.persist(Order.createForBackupAccept(auction, buyer, 140000L, 3000L, LocalDateTime.now().plusDays(1)));
    }

    private void persistMatch(Long goalId, Auction auction, boolean matched, double score, String reason) {
        entityManager.persist(PurchaseGoalMatch.create(goalId, auction.getId(), matched, score, reason, "nb990", LocalDateTime.now()));
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void 본인_소유_Goal은_상세조회에_성공한다() {
        User owner = persistUser("owner@example.com");
        PurchaseGoal goal = persistGoal(owner);

        PurchaseGoalDetailResponse response = purchaseGoalQueryService.getGoal(goal.getId(), owner.getId());

        assertThat(response.id()).isEqualTo(goal.getId());
        assertThat(response.userId()).isEqualTo(owner.getId());
        assertThat(response.participationCount()).isZero();
        assertThat(response.wonCount()).isZero();
        assertThat(response.participations()).isEmpty();
    }

    @Test
    void 다른_사용자의_Goal을_조회하면_PurchaseGoalAccessDeniedException을_던진다() {
        User owner = persistUser("owner2@example.com");
        User stranger = persistUser("stranger@example.com");
        PurchaseGoal goal = persistGoal(owner);

        assertThatThrownBy(() -> purchaseGoalQueryService.getGoal(goal.getId(), stranger.getId()))
                .isInstanceOf(PurchaseGoalAccessDeniedException.class);
    }

    @Test
    void 예약_진행중_낙찰_패배_경매가_섞인_상세조회는_각각_올바른_상태로_보고한다() {
        User seller = persistUser("seller3@example.com");
        User owner = persistUser("owner3@example.com");
        PurchaseGoal goal = persistGoal(owner);

        Auction scheduled = persistScheduledAuction(persistProduct(seller));
        Auction live = persistLiveAuction(persistProduct(seller));
        Auction won = persistEndedAuction(persistProduct(seller));
        Auction lost = persistEndedAuction(persistProduct(seller));

        persistAutoBidSetting(scheduled, owner, goal.getId());
        persistAutoBidSetting(live, owner, goal.getId());
        persistAutoBidSetting(won, owner, goal.getId());
        persistAutoBidSetting(lost, owner, goal.getId());
        persistOrder(won, owner);
        flushAndClear();

        PurchaseGoalDetailResponse response = purchaseGoalQueryService.getGoal(goal.getId(), owner.getId());

        assertThat(response.participationCount()).isEqualTo(4);
        assertThat(response.wonCount()).isEqualTo(1);
        assertThat(response.participations())
                .extracting(PurchaseGoalParticipationResponse::auctionId, PurchaseGoalParticipationResponse::status)
                .containsExactlyInAnyOrder(
                        tuple(scheduled.getId(), "SCHEDULED"),
                        tuple(live.getId(), "LIVE"),
                        tuple(won.getId(), "WON"),
                        tuple(lost.getId(), "LOST")
                );
    }

    @Test
    void 취소된_경매는_패배로_집계한다() {
        User seller = persistUser("seller4@example.com");
        User owner = persistUser("owner4@example.com");
        PurchaseGoal goal = persistGoal(owner);

        Auction scheduled = persistScheduledAuction(persistProduct(seller));
        scheduled.cancel();
        persistAutoBidSetting(scheduled, owner, goal.getId());
        flushAndClear();

        PurchaseGoalDetailResponse response = purchaseGoalQueryService.getGoal(goal.getId(), owner.getId());

        assertThat(response.participations())
                .extracting(PurchaseGoalParticipationResponse::status)
                .containsExactly("LOST");
    }

    @Test
    void 다른_사용자의_낙찰_Order는_내_낙찰로_잡히지_않는다() {
        User seller = persistUser("seller5@example.com");
        User owner = persistUser("owner5@example.com");
        User otherWinner = persistUser("other5@example.com");
        PurchaseGoal goal = persistGoal(owner);

        Auction ended = persistEndedAuction(persistProduct(seller));
        persistAutoBidSetting(ended, owner, goal.getId());
        persistOrder(ended, otherWinner);
        flushAndClear();

        PurchaseGoalDetailResponse response = purchaseGoalQueryService.getGoal(goal.getId(), owner.getId());

        assertThat(response.wonCount()).isZero();
        assertThat(response.participations()).extracting(PurchaseGoalParticipationResponse::status).containsExactly("LOST");
    }

    @Test
    void 목록조회에도_참여_횟수와_낙찰_횟수가_반영된다() {
        User seller = persistUser("seller6@example.com");
        User owner = persistUser("owner6@example.com");
        PurchaseGoal engagedGoal = persistGoal(owner);
        PurchaseGoal untouchedGoal = persistGoal(owner);

        Auction live = persistLiveAuction(persistProduct(seller));
        persistAutoBidSetting(live, owner, engagedGoal.getId());
        flushAndClear();

        List<PurchaseGoalResponse> responses = purchaseGoalQueryService.getMyGoals(owner.getId());

        PurchaseGoalResponse engagedResponse = responses.stream()
                .filter(r -> r.id().equals(engagedGoal.getId())).findFirst().orElseThrow();
        PurchaseGoalResponse untouchedResponse = responses.stream()
                .filter(r -> r.id().equals(untouchedGoal.getId())).findFirst().orElseThrow();

        assertThat(engagedResponse.participationCount()).isEqualTo(1);
        assertThat(engagedResponse.wonCount()).isZero();
        assertThat(untouchedResponse.participationCount()).isZero();
    }

    // Day 8: wonCount를 0/1로 제한하지 않는다는 것을 직접 확인한다. 한 Goal이 서로 다른 두 경매에서
    // 각각 Order를 갖는 경우(하나는 결제 전, 하나는 결제 기한 만료 - 결제 완료와 무관하게 세야 한다)
    // 목록·상세 모두 wonCount=2여야 한다.
    @Test
    void 서로_다른_두_경매에서_Order가_생기면_wonCount는_2다() {
        User seller = persistUser("seller7@example.com");
        User owner = persistUser("owner7@example.com");
        PurchaseGoal goal = persistGoal(owner);

        Auction wonA = persistEndedAuction(persistProduct(seller));
        Auction wonB = persistEndedAuction(persistProduct(seller));
        persistAutoBidSetting(wonA, owner, goal.getId());
        persistAutoBidSetting(wonB, owner, goal.getId());
        persistOrderWithStatus(wonA, owner, OrderStatus.PAYMENT_PENDING);
        // 결제 기한이 만료돼도(=결제를 완료하지 못했어도) 낙찰 자체는 이미 일어난 사실이라 그대로 센다.
        persistOrderWithStatus(wonB, owner, OrderStatus.PAYMENT_EXPIRED);
        flushAndClear();

        PurchaseGoalDetailResponse detail = purchaseGoalQueryService.getGoal(goal.getId(), owner.getId());
        assertThat(detail.wonCount()).isEqualTo(2);
        assertThat(detail.participations()).extracting(PurchaseGoalParticipationResponse::status)
                .containsExactlyInAnyOrder("WON", "WON");

        PurchaseGoalResponse listItem = purchaseGoalQueryService.getMyGoals(owner.getId()).stream()
                .filter(r -> r.id().equals(goal.getId())).findFirst().orElseThrow();
        assertThat(listItem.wonCount()).isEqualTo(2);
        assertThat(listItem.participationCount()).isEqualTo(2);
    }

    // 수동 BackupOffer 수락으로 생긴 Order도 정상 판매 낙찰과 동일하게 wonCount에 반영돼야 한다 -
    // 이 테스트는 그 경로만 시뮬레이션할 뿐 실제 BackupOffer/Agent 상태 전이 로직은 건드리지 않는다.
    @Test
    void BackupOffer_수락으로_생긴_Order도_낙찰로_센다() {
        User seller = persistUser("seller8@example.com");
        User owner = persistUser("owner8@example.com");
        PurchaseGoal goal = persistGoal(owner);

        Auction directWin = persistEndedAuction(persistProduct(seller));
        Auction backupWin = persistEndedAuction(persistProduct(seller));
        persistAutoBidSetting(directWin, owner, goal.getId());
        persistAutoBidSetting(backupWin, owner, goal.getId());
        persistOrder(directWin, owner);
        persistBackupAcceptOrder(backupWin, owner);
        flushAndClear();

        PurchaseGoalDetailResponse response = purchaseGoalQueryService.getGoal(goal.getId(), owner.getId());

        assertThat(response.wonCount()).isEqualTo(2);
        assertThat(response.participationCount()).isEqualTo(2);
    }

    @Test
    void 평가_이력은_참여_이력과_구분해서_조회되고_참여_횟수에_들어가지_않는다() {
        User seller = persistUser("seller9@example.com");
        User owner = persistUser("owner9@example.com");
        PurchaseGoal goal = persistGoal(owner);

        Auction registered = persistLiveAuction(persistProduct(seller));
        Auction evaluatedOnly = persistLiveAuction(persistProduct(seller));
        persistAutoBidSetting(registered, owner, goal.getId());
        persistMatch(goal.getId(), registered, true, 0.9, "등록까지 이어짐");
        // evaluatedOnly는 Matcher가 평가만 했고(matched=false 포함 가능) AutoBid 등록으로는
        // 이어지지 않았다 - 참여 이력에는 없어야 한다.
        persistMatch(goal.getId(), evaluatedOnly, false, 0.2, "브랜드 불일치");
        flushAndClear();

        PurchaseGoalDetailResponse detail = purchaseGoalQueryService.getGoal(goal.getId(), owner.getId());
        assertThat(detail.participationCount()).isEqualTo(1);
        assertThat(detail.participations()).extracting(PurchaseGoalParticipationResponse::auctionId)
                .containsExactly(registered.getId());

        List<PurchaseGoalMatchHistoryResponse> matchHistory = purchaseGoalQueryService.getMatchHistory(goal.getId(), owner.getId());
        assertThat(matchHistory).hasSize(2);
        assertThat(matchHistory).extracting(PurchaseGoalMatchHistoryResponse::auctionId, PurchaseGoalMatchHistoryResponse::matched)
                .containsExactlyInAnyOrder(
                        tuple(registered.getId(), true),
                        tuple(evaluatedOnly.getId(), false)
                );
    }

    @Test
    void 다른_사용자의_평가_이력은_조회할_수_없다() {
        User seller = persistUser("seller10@example.com");
        User owner = persistUser("owner10@example.com");
        User stranger = persistUser("stranger10@example.com");
        PurchaseGoal goal = persistGoal(owner);
        persistMatch(goal.getId(), persistLiveAuction(persistProduct(seller)), true, 0.5, "사유");
        flushAndClear();

        assertThatThrownBy(() -> purchaseGoalQueryService.getMatchHistory(goal.getId(), stranger.getId()))
                .isInstanceOf(PurchaseGoalAccessDeniedException.class);
    }
}
