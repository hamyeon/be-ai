package com.vintic.backend.purchasegoal.service;

import com.vintic.backend.ai.purchase.dto.GoalCondition;
import com.vintic.backend.common.exception.InvalidPurchaseGoalException;
import com.vintic.backend.common.exception.UserNotFoundException;
import com.vintic.backend.config.ClockConfig;
import com.vintic.backend.purchasegoal.domain.PurchaseGoal;
import com.vintic.backend.purchasegoal.domain.PurchaseGoalStatus;
import com.vintic.backend.purchasegoal.dto.CreatePurchaseGoalRequest;
import com.vintic.backend.purchasegoal.dto.PurchaseGoalCancelResponse;
import com.vintic.backend.purchasegoal.dto.PurchaseGoalResponse;
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
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// @DataJpaTest 슬라이스에 서비스를 직접 Import해 실제 저장 결과를 검증한다
// (AutoBidCommandServiceTest와 동일한 관례). Clock은 TestClockConfig로 고정해
// "마감 시각이 실제로 미래인지"를 결정적으로 검증한다.
@DataJpaTest
@Import({PurchaseGoalCommandService.class, TestClockConfig.class})
class PurchaseGoalCommandServiceTest {

    @Autowired
    private PurchaseGoalCommandService purchaseGoalCommandService;

    @Autowired
    private PurchaseGoalRepository purchaseGoalRepository;

    @Autowired
    private EntityManager entityManager;

    private User persistUser(String email) {
        User user = User.register(email, email, null);
        entityManager.persist(user);
        return user;
    }

    private OffsetDateTime fixedNowOffset() {
        return OffsetDateTime.ofInstant(TestClockConfig.FIXED_INSTANT, ClockConfig.APP_ZONE);
    }

    private CreatePurchaseGoalRequest requestWithDeadline(OffsetDateTime deadline) {
        return new CreatePurchaseGoalRequest(
                "New Balance", "nb990", "뉴발란스 990", "A",
                270, 150000L, "박스 있으면 좋음", deadline
        );
    }

    private PurchaseGoal persistGoal(User owner) {
        LocalDateTime now = LocalDateTime.now();
        PurchaseGoal goal = PurchaseGoal.create(
                owner, "New Balance", "nb990", "뉴발란스 990",
                GoalCondition.A, 270, 150000L, null, now.plusDays(7), now
        );
        return purchaseGoalRepository.saveAndFlush(goal);
    }

    @Test
    void 직접_입력으로_등록하면_ACTIVE_상태로_저장되고_참여_경매는_null이다() {
        User user = persistUser("agent-user@example.com");
        CreatePurchaseGoalRequest request = requestWithDeadline(fixedNowOffset().plusDays(7));

        PurchaseGoalResponse response = purchaseGoalCommandService.createGoal(request, user.getId());

        assertThat(response.id()).isNotNull();
        assertThat(response.userId()).isEqualTo(user.getId());
        assertThat(response.status()).isEqualTo("ACTIVE");
        assertThat(response.currentAuctionId()).isNull();
        assertThat(response.minCondition()).isEqualTo("A");
        assertThat(purchaseGoalRepository.findById(response.id())).isPresent();
    }

    @Test
    void 인증된_사용자_id로_PurchaseGoal이_연결된다() {
        User owner = persistUser("owner@example.com");
        CreatePurchaseGoalRequest request = requestWithDeadline(fixedNowOffset().plusDays(7));

        PurchaseGoalResponse response = purchaseGoalCommandService.createGoal(request, owner.getId());

        assertThat(response.userId()).isEqualTo(owner.getId());
    }

    @Test
    void 브랜드와_사이즈가_없어도_유효한_요청이면_ACTIVE로_등록된다() {
        User user = persistUser("no-brand-size@example.com");
        CreatePurchaseGoalRequest request = new CreatePurchaseGoalRequest(
                null, "nb990", "뉴발란스 990", "A",
                null, 150000L, null, fixedNowOffset().plusDays(7)
        );

        PurchaseGoalResponse response = purchaseGoalCommandService.createGoal(request, user.getId());

        assertThat(response.id()).isNotNull();
        assertThat(response.brand()).isNull();
        assertThat(response.sizeKr()).isNull();
        assertThat(response.status()).isEqualTo("ACTIVE");
        assertThat(purchaseGoalRepository.findById(response.id())).isPresent();
    }

    @Test
    void 존재하지_않는_사용자면_UserNotFoundException을_던진다() {
        CreatePurchaseGoalRequest request = requestWithDeadline(fixedNowOffset().plusDays(7));

        assertThatThrownBy(() -> purchaseGoalCommandService.createGoal(request, 999L))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void deadline이_과거이면_InvalidPurchaseGoalException을_던지고_저장하지_않는다() {
        User user = persistUser("agent-user2@example.com");
        CreatePurchaseGoalRequest request = requestWithDeadline(fixedNowOffset().minusDays(1));

        assertThatThrownBy(() -> purchaseGoalCommandService.createGoal(request, user.getId()))
                .isInstanceOf(InvalidPurchaseGoalException.class);
        assertThat(purchaseGoalRepository.count()).isZero();
    }

    @Test
    void deadline이_현재_시각과_같으면_InvalidPurchaseGoalException을_던진다() {
        User user = persistUser("agent-user3@example.com");
        CreatePurchaseGoalRequest request = requestWithDeadline(fixedNowOffset());

        assertThatThrownBy(() -> purchaseGoalCommandService.createGoal(request, user.getId()))
                .isInstanceOf(InvalidPurchaseGoalException.class);
    }

    @Test
    void minCondition_라벨이_유효하지_않으면_InvalidPurchaseGoalException을_던진다() {
        User user = persistUser("agent-user4@example.com");
        CreatePurchaseGoalRequest request = new CreatePurchaseGoalRequest(
                "New Balance", "nb990", "뉴발란스 990", "S_PLUS",
                270, 150000L, null, fixedNowOffset().plusDays(7)
        );

        assertThatThrownBy(() -> purchaseGoalCommandService.createGoal(request, user.getId()))
                .isInstanceOf(InvalidPurchaseGoalException.class);
        assertThat(purchaseGoalRepository.count()).isZero();
    }

    @Test
    void ACTIVE_상태의_Goal을_취소하면_CANCELLED로_전이한다() {
        User owner = persistUser("cancel-active@example.com");
        PurchaseGoal goal = persistGoal(owner);

        PurchaseGoalCancelResponse response = purchaseGoalCommandService.cancelGoal(goal.getId(), owner.getId());

        assertThat(response.status()).isEqualTo(PurchaseGoalStatus.CANCELLED);
        assertThat(purchaseGoalRepository.findById(goal.getId()).orElseThrow().getStatus())
                .isEqualTo(PurchaseGoalStatus.CANCELLED);
    }

    @Test
    void ENGAGED_상태의_Goal을_취소하면_CANCEL_REQUESTED로_전이하고_참여_경매는_그대로_남는다() {
        User owner = persistUser("cancel-engaged@example.com");
        PurchaseGoal goal = persistGoal(owner);
        // Day 5(실제 Agent 참여 로직) 이전이라 ACTIVE->ENGAGED 전이 API가 아직 없다 - 테스트
        // 픽스처로만 상태를 직접 맞춘다(AutoBidCommandServiceTest의 ReflectionTestUtils 관례와 동일).
        ReflectionTestUtils.setField(goal, "status", PurchaseGoalStatus.ENGAGED);
        ReflectionTestUtils.setField(goal, "currentAuctionId", 42L);
        purchaseGoalRepository.saveAndFlush(goal);

        PurchaseGoalCancelResponse response = purchaseGoalCommandService.cancelGoal(goal.getId(), owner.getId());

        assertThat(response.status()).isEqualTo(PurchaseGoalStatus.CANCEL_REQUESTED);
        PurchaseGoal reloaded = purchaseGoalRepository.findById(goal.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PurchaseGoalStatus.CANCEL_REQUESTED);
        // 참여 중 AutoBid는 이 취소가 건드리지 않는다 - currentAuctionId도 그대로다.
        assertThat(reloaded.getCurrentAuctionId()).isEqualTo(42L);
    }
}
