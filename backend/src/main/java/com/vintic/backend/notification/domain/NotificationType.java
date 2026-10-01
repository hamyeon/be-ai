package com.vintic.backend.notification.domain;

// #75: 실제 화면 요구(상단 벨 → unread badge → 목록 → 클릭 시 이동)에 필요한 최소 3종만 채택한다.
// AUCTION_STARTED/PAYMENT_COMPLETED 등 다른 후보는 이번 범위가 아니다 - 미래용으로 선추가하지 않는다.
//
// Day 7: PURCHASE_AGENT_ENGAGED/PURCHASE_AGENT_LOST 추가. 낙찰은 별도 타입을 만들지 않는다 -
// 기존 AUCTION_WON이 Agent가 낙찰한 경우에도 그대로 발생한다(승자가 누구든 동일 경로).
public enum NotificationType {
    AUCTION_WON,
    BACKUP_OFFER_CREATED,
    PAYMENT_EXPIRED,
    PURCHASE_AGENT_ENGAGED,
    PURCHASE_AGENT_LOST
}
