package com.vintic.backend.purchasegoal.service;

import com.vintic.backend.ai.purchase.dto.GoalCondition;
import com.vintic.backend.common.exception.PurchaseGoalAccessDeniedException;
import com.vintic.backend.purchasegoal.domain.PurchaseGoal;
import com.vintic.backend.purchasegoal.dto.PurchaseGoalResponse;
import com.vintic.backend.purchasegoal.repository.PurchaseGoalRepository;
import com.vintic.backend.user.domain.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void 본인_소유_Goal은_상세조회에_성공한다() {
        User owner = persistUser("owner@example.com");
        PurchaseGoal goal = persistGoal(owner);

        PurchaseGoalResponse response = purchaseGoalQueryService.getGoal(goal.getId(), owner.getId());

        assertThat(response.id()).isEqualTo(goal.getId());
        assertThat(response.userId()).isEqualTo(owner.getId());
    }

    @Test
    void 다른_사용자의_Goal을_조회하면_PurchaseGoalAccessDeniedException을_던진다() {
        User owner = persistUser("owner2@example.com");
        User stranger = persistUser("stranger@example.com");
        PurchaseGoal goal = persistGoal(owner);

        assertThatThrownBy(() -> purchaseGoalQueryService.getGoal(goal.getId(), stranger.getId()))
                .isInstanceOf(PurchaseGoalAccessDeniedException.class);
    }
}
