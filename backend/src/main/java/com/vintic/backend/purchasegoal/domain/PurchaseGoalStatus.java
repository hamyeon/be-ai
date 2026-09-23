package com.vintic.backend.purchasegoal.domain;

// 설계가 정한 6개 상태. Day 1은 생성 시 ACTIVE만 쓴다 - 나머지 전이 로직(ENGAGED로의 진입,
// 취소 요청/완료, 만료 등)은 그 흐름을 실제로 구현하는 Day에서 추가한다. DRAFT는 여기 없다 -
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
