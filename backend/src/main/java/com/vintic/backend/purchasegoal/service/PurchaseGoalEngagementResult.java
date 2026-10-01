package com.vintic.backend.purchasegoal.service;

// PurchaseGoalEngagementService/PurchaseGoalEngagementTransactionService의 내부 결과.
// Day 6 Scheduler가 goal마다 이 결과를 보고 다음 goal로 넘어가면 된다 - 실패해도 예외로
// 올리지 않는다(참여 시도 자체가 "실패할 수 있는 정상 경로"이기 때문). 공개 API 응답이 아니다.
public record PurchaseGoalEngagementResult(
        boolean engaged,
        String reason,
        Long auctionId,
        Long autoBidSettingId
) {

    public static PurchaseGoalEngagementResult notEngaged(String reason) {
        return new PurchaseGoalEngagementResult(false, reason, null, null);
    }

    public static PurchaseGoalEngagementResult engaged(Long auctionId, Long autoBidSettingId) {
        return new PurchaseGoalEngagementResult(true, null, auctionId, autoBidSettingId);
    }
}
