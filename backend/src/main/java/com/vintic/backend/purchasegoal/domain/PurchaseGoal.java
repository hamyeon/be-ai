package com.vintic.backend.purchasegoal.domain;

import com.vintic.backend.ai.purchase.dto.GoalCondition;
import com.vintic.backend.common.exception.InvalidPurchaseGoalStatusException;
import com.vintic.backend.user.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

// 사용자가 POST /api/purchase-goals로 직접 확정한 구매 목표. GoalDraft(AI 초안)와 달리 여기
// 저장된 값은 사람이 확인·확정한 값이라 confidence/warnings를 갖지 않는다.
//
// currentAuctionId는 FK 연관관계를 맺지 않는다 - Notification.auctionId와 같은 "참조값만,
// 연관관계 없음" 패턴이다. 이 필드에 실제로 값을 채우는 매칭/참여 로직은 이후 Day 작업이다 -
// Day 1은 생성 시 항상 null로 둔다.
@Entity
@Table(
        name = "purchase_goals",
        indexes = @Index(name = "idx_purchase_goal_user", columnList = "user_id")
)
public class PurchaseGoal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // GoalDraft.brand와 동일하게 null일 수 있다(modelQuery만으로 목표를 특정한 경우).
    @Column
    private String brand;

    // 시세 카탈로그 밖의 모델은 null일 수 있다(ai.purchase.dto.GoalDraft와 동일한 정책) -
    // 그 경우 매칭 단계(Day 4)가 후보를 찾지 못할 뿐, 등록 자체를 막을 이유는 아니다.
    @Column(name = "model_key")
    private String modelKey;

    @Column(nullable = false)
    private String modelQuery;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GoalCondition minCondition;

    // 값이 있을 때만 이후 hard filter 조건으로 쓰인다 - 없으면 사이즈로 거르지 않는다.
    @Column
    private Integer sizeKr;

    @Column(nullable = false)
    private Long hardMaxAmount;

    @Column(length = 1000)
    private String freeTextConditions;

    @Column(nullable = false)
    private LocalDateTime deadline;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PurchaseGoalStatus status;

    @Column(name = "current_auction_id")
    private Long currentAuctionId;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    protected PurchaseGoal() {
    }

    public static PurchaseGoal create(
            User user,
            String brand,
            String modelKey,
            String modelQuery,
            GoalCondition minCondition,
            Integer sizeKr,
            Long hardMaxAmount,
            String freeTextConditions,
            LocalDateTime deadline,
            LocalDateTime createdAt
    ) {
        if (user == null) {
            throw new IllegalArgumentException("사용자는 필수입니다.");
        }
        if (modelQuery == null || modelQuery.isBlank()) {
            throw new IllegalArgumentException("모델 설명은 필수입니다.");
        }
        if (minCondition == null) {
            throw new IllegalArgumentException("최소 상태 등급은 필수입니다.");
        }
        if (sizeKr != null && sizeKr <= 0) {
            throw new IllegalArgumentException("사이즈는 0보다 커야 합니다.");
        }
        if (hardMaxAmount == null || hardMaxAmount <= 0) {
            throw new IllegalArgumentException("예산 상한은 0보다 커야 합니다.");
        }
        if (deadline == null) {
            throw new IllegalArgumentException("마감 시각은 필수입니다.");
        }

        PurchaseGoal goal = new PurchaseGoal();
        goal.user = user;
        goal.brand = brand;
        goal.modelKey = modelKey;
        goal.modelQuery = modelQuery;
        goal.minCondition = minCondition;
        goal.sizeKr = sizeKr;
        goal.hardMaxAmount = hardMaxAmount;
        goal.freeTextConditions = freeTextConditions;
        goal.deadline = deadline;
        goal.status = PurchaseGoalStatus.ACTIVE;
        goal.currentAuctionId = null;
        goal.createdAt = createdAt;
        goal.updatedAt = createdAt;
        return goal;
    }

    // ACTIVE -> CANCELLED, ENGAGED -> CANCEL_REQUESTED. 참여 중(ENGAGED) AutoBid는 여기서
    // 건드리지 않는다 - 실제로 정리(cancel)하는 것은 Agent 참여 로직(Day 5)의 책임이다. 이
    // 메서드는 사용자의 취소 "의사"만 기록한다. 그 외 상태(CANCEL_REQUESTED/FULFILLED/
    // CANCELLED/EXPIRED)에서의 재요청은 전부 거절한다 - 종료된 목표를 다시 취소하거나 이미 취소
    // 요청한 목표를 또 취소 요청하는 것은 의미가 없다.
    public void cancel(LocalDateTime updatedAt) {
        switch (status) {
            case ACTIVE -> status = PurchaseGoalStatus.CANCELLED;
            case ENGAGED -> status = PurchaseGoalStatus.CANCEL_REQUESTED;
            default -> throw new InvalidPurchaseGoalStatusException(
                    "ACTIVE/ENGAGED 상태에서만 취소할 수 있습니다. 현재 상태: " + status
            );
        }
        this.updatedAt = updatedAt;
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public String getBrand() {
        return brand;
    }

    public String getModelKey() {
        return modelKey;
    }

    public String getModelQuery() {
        return modelQuery;
    }

    public GoalCondition getMinCondition() {
        return minCondition;
    }

    public Integer getSizeKr() {
        return sizeKr;
    }

    public Long getHardMaxAmount() {
        return hardMaxAmount;
    }

    public String getFreeTextConditions() {
        return freeTextConditions;
    }

    public LocalDateTime getDeadline() {
        return deadline;
    }

    public PurchaseGoalStatus getStatus() {
        return status;
    }

    public Long getCurrentAuctionId() {
        return currentAuctionId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
