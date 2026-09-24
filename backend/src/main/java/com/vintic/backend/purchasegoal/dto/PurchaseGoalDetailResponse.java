package com.vintic.backend.purchasegoal.dto;

import com.vintic.backend.common.util.TimePolicy;
import com.vintic.backend.purchasegoal.domain.PurchaseGoal;

import java.time.OffsetDateTime;
import java.util.List;

// GET /api/purchase-goals/{id} 전용. PurchaseGoalResponse(목록)와 같은 필드를 그대로 갖고,
// 참여 경매 목록(participations)만 추가한다 - 목록 응답 모양은 건드리지 않는다.
public record PurchaseGoalDetailResponse(
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
        OffsetDateTime createdAt,
        int participationCount,
        int wonCount,
        List<PurchaseGoalParticipationResponse> participations
) {
    public static PurchaseGoalDetailResponse from(PurchaseGoal goal, List<PurchaseGoalParticipationResponse> participations) {
        int wonCount = (int) participations.stream().filter(p -> "WON".equals(p.status())).count();
        return new PurchaseGoalDetailResponse(
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
                TimePolicy.toApiTime(goal.getCreatedAt()),
                participations.size(),
                wonCount,
                participations
        );
    }
}
