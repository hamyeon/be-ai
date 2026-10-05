package com.vintic.backend.purchasegoal.domain;

// 설계가 정한 6개 상태. 생성 시에는 ACTIVE다 - 나머지 전이(ENGAGED로의 진입은 Day 5
// PurchaseGoalEngagementTransactionService, 취소 요청은 PurchaseGoalCommandService, 만료/결과 반영은
// Day 6 PurchaseGoalExpirationService/PurchaseGoalResultObservationService)는 구현돼 있다. DRAFT는 여기 없다 -
// GoalDraft는 사용자가 확정하기 전의 값이라 DB에 남지 않는다(POST /api/purchase-goals로
// 확정된 순간부터가 PurchaseGoal이다).
public enum PurchaseGoalStatus {
    ACTIVE,
    ENGAGED,
    CANCEL_REQUESTED,
    FULFILLED,
    CANCELLED,
    EXPIRED
}
