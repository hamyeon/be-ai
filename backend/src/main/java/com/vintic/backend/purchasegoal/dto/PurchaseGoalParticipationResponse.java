package com.vintic.backend.purchasegoal.dto;

// Goal 상세 화면의 참여 이력 한 건. auctionId는 경매 상세로 이동할 ID다.
// status: SCHEDULED(참여 예정 - AutoBid는 등록됐지만 경매 시작 전) | LIVE(진행 중) |
//         WON(낙찰) | LOST(패배 또는 경매 취소).
public record PurchaseGoalParticipationResponse(
        Long auctionId,
        String status
) {
}
