package com.vintic.backend.purchasegoal.dto;

import com.vintic.backend.common.util.TimePolicy;
import com.vintic.backend.purchasegoal.domain.PurchaseGoal;

import java.time.OffsetDateTime;

// participationCount/wonCount는 Day 7 추가 필드다 - purchaseGoalId가 연결된 AutoBidSetting을
// 경매 단위로 센 값이다(Matcher 평가만 한 경매는 제외). wonCount는 그 경매들 중 본인 Order가
// 있는 경매 수(=실제 낙찰 건수, 결제 완료 여부와 무관)이며 1로 상한을 두지 않는다 - 한 Goal이
// 여러 경매에 순차 참여해 두 번 이상 낙찰하면(수동 BackupOffer 수락 포함) 2 이상이 될 수 있다.
// 기존 필드는 그대로 두고 뒤에 추가했다 - 기존 소비자가 읽던 필드는 그대로다.
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
        OffsetDateTime createdAt,
        int participationCount,
        int wonCount
) {
    public static PurchaseGoalResponse from(PurchaseGoal goal, int participationCount, int wonCount) {
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
                TimePolicy.toApiTime(goal.getCreatedAt()),
                participationCount,
                wonCount
        );
    }
}
