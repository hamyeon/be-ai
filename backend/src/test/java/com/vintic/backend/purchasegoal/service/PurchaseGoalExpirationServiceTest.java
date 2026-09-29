package com.vintic.backend.purchasegoal.service;

import com.vintic.backend.ai.purchase.dto.GoalCondition;
import com.vintic.backend.config.ClockConfig;
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

import static org.assertj.core.api.Assertions.assertThat;

// Day 6 phase 1(만료)만 검증한다 - ENGAGED/CANCEL_REQUESTED는 deadline이 지나도 건드리지 않는다는
// 것이 이 phase의 핵심 불변식이다(phase 2의 책임).
@DataJpaTest
@Import({PurchaseGoalExpirationService.class, TestClockConfig.class})
class PurchaseGoalExpirationServiceTest {

    private static final LocalDateTime FIXED_NOW = LocalDateTime.ofInstant(TestClockConfig.FIXED_INSTANT, ClockConfig.APP_ZONE);

    @Autowired
    private PurchaseGoalExpirationService expirationService;

    @Autowired
    private PurchaseGoalRepository purchaseGoalRepository;

    @Autowired
    private EntityManager entityManager;

    private User persistUser(String email) {
        User user = User.register(email, email, null);
        entityManager.persist(user);
        return user;
    }

    private PurchaseGoal persistGoal(User owner, PurchaseGoalStatus status, LocalDateTime deadline) {
        PurchaseGoal goal = PurchaseGoal.create(
                owner, "New Balance", "nb990", "뉴발란스 990",
                GoalCondition.B, null, 200000L, null, deadline, FIXED_NOW.minusDays(1)
        );
        if (status != PurchaseGoalStatus.ACTIVE) {
            ReflectionTestUtils.setField(goal, "status", status);
        }
        return purchaseGoalRepository.saveAndFlush(goal);
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void deadline이_지난_ACTIVE_Goal은_EXPIRED로_전이한다() {
        User owner = persistUser("owner1@vintic.local");
        PurchaseGoal goal = persistGoal(owner, PurchaseGoalStatus.ACTIVE, FIXED_NOW.minusMinutes(1));
        flushAndClear();

        int expired = expirationService.expireDueGoals();

        assertThat(expired).isEqualTo(1);
        assertThat(purchaseGoalRepository.findById(goal.getId()).orElseThrow().getStatus()).isEqualTo(PurchaseGoalStatus.EXPIRED);
    }

    @Test
    void deadline이_now와_정확히_같은_ACTIVE_Goal도_EXPIRED로_전이한다() {
        User owner = persistUser("owner2@vintic.local");
        PurchaseGoal goal = persistGoal(owner, PurchaseGoalStatus.ACTIVE, FIXED_NOW);
        flushAndClear();

        int expired = expirationService.expireDueGoals();

        assertThat(expired).isEqualTo(1);
        assertThat(purchaseGoalRepository.findById(goal.getId()).orElseThrow().getStatus()).isEqualTo(PurchaseGoalStatus.EXPIRED);
    }

    @Test
    void deadline이_아직_안_지난_ACTIVE_Goal은_그대로_ACTIVE다() {
        User owner = persistUser("owner3@vintic.local");
        PurchaseGoal goal = persistGoal(owner, PurchaseGoalStatus.ACTIVE, FIXED_NOW.plusMinutes(1));
        flushAndClear();

        int expired = expirationService.expireDueGoals();

        assertThat(expired).isEqualTo(0);
        assertThat(purchaseGoalRepository.findById(goal.getId()).orElseThrow().getStatus()).isEqualTo(PurchaseGoalStatus.ACTIVE);
    }

    @Test
    void ENGAGED_Goal은_deadline이_지나도_만료시키지_않는다() {
        User owner = persistUser("owner4@vintic.local");
        PurchaseGoal goal = persistGoal(owner, PurchaseGoalStatus.ENGAGED, FIXED_NOW.minusDays(1));
        flushAndClear();

        int expired = expirationService.expireDueGoals();

        assertThat(expired).isEqualTo(0);
        assertThat(purchaseGoalRepository.findById(goal.getId()).orElseThrow().getStatus()).isEqualTo(PurchaseGoalStatus.ENGAGED);
    }

    @Test
    void CANCEL_REQUESTED_Goal도_deadline이_지나도_만료시키지_않는다() {
        User owner = persistUser("owner5@vintic.local");
        PurchaseGoal goal = persistGoal(owner, PurchaseGoalStatus.CANCEL_REQUESTED, FIXED_NOW.minusDays(1));
        flushAndClear();

        int expired = expirationService.expireDueGoals();

        assertThat(expired).isEqualTo(0);
        assertThat(purchaseGoalRepository.findById(goal.getId()).orElseThrow().getStatus()).isEqualTo(PurchaseGoalStatus.CANCEL_REQUESTED);
    }

    @Test
    void 여러_Goal_중_대상만_골라_한_번에_만료시킨다() {
        User owner = persistUser("owner6@vintic.local");
        PurchaseGoal dueActive1 = persistGoal(owner, PurchaseGoalStatus.ACTIVE, FIXED_NOW.minusHours(2));
        PurchaseGoal dueActive2 = persistGoal(owner, PurchaseGoalStatus.ACTIVE, FIXED_NOW.minusMinutes(5));
        PurchaseGoal notDueActive = persistGoal(owner, PurchaseGoalStatus.ACTIVE, FIXED_NOW.plusDays(1));
        PurchaseGoal engaged = persistGoal(owner, PurchaseGoalStatus.ENGAGED, FIXED_NOW.minusHours(1));
        flushAndClear();

        int expired = expirationService.expireDueGoals();

        assertThat(expired).isEqualTo(2);
        assertThat(purchaseGoalRepository.findById(dueActive1.getId()).orElseThrow().getStatus()).isEqualTo(PurchaseGoalStatus.EXPIRED);
        assertThat(purchaseGoalRepository.findById(dueActive2.getId()).orElseThrow().getStatus()).isEqualTo(PurchaseGoalStatus.EXPIRED);
        assertThat(purchaseGoalRepository.findById(notDueActive.getId()).orElseThrow().getStatus()).isEqualTo(PurchaseGoalStatus.ACTIVE);
        assertThat(purchaseGoalRepository.findById(engaged.getId()).orElseThrow().getStatus()).isEqualTo(PurchaseGoalStatus.ENGAGED);
    }
}
