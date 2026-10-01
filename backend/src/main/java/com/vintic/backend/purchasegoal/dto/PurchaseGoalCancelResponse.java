package com.vintic.backend.purchasegoal.dto;

import com.vintic.backend.purchasegoal.domain.PurchaseGoalStatus;

import java.time.OffsetDateTime;

public record PurchaseGoalCancelResponse(
        Long id,
        PurchaseGoalStatus status,
        OffsetDateTime updatedAt
) {
}
