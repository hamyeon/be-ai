package com.vintic.backend.purchasegoal.dto;

import com.vintic.backend.common.util.TimePolicy;
import com.vintic.backend.purchasegoal.domain.PurchaseGoal;

import java.time.OffsetDateTime;

public record PurchaseGoalResponse(
        Long id,
        Long userId,
        String brand,
        String modelKey,
        String modelQuery,
        String minCondition,
        Integer sizeKr,
        Long hardMaxAmount,
        String freeTextConditions,
        OffsetDateTime deadline,
        String status,
        Long currentAuctionId,
        OffsetDateTime createdAt
) {
    public static PurchaseGoalResponse from(PurchaseGoal goal) {
        return new PurchaseGoalResponse(
                goal.getId(),
                goal.getUser().getId(),
                goal.getBrand(),
                goal.getModelKey(),
                goal.getModelQuery(),
                goal.getMinCondition().name(),
                goal.getSizeKr(),
                goal.getHardMaxAmount(),
                goal.getFreeTextConditions(),
                TimePolicy.toApiTime(goal.getDeadline()),
                goal.getStatus().name(),
                goal.getCurrentAuctionId(),
                TimePolicy.toApiTime(goal.getCreatedAt())
        );
    }
}
