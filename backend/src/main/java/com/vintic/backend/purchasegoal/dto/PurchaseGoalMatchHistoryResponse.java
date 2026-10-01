package com.vintic.backend.purchasegoal.dto;

import com.vintic.backend.purchasegoal.domain.PurchaseGoalMatch;

// GET /api/purchase-goals/{id}/matches 전용. Day 4 Matcher가 평가만 한 경매(등록까지 가지
// 않은 경매 포함)를 참여 이력(PurchaseGoalDetailResponse.participations)과 구분해서 보여준다 -
// matched=false도 포함한다.
public record PurchaseGoalMatchHistoryResponse(
        Long auctionId,
        boolean matched,
        double semanticScore,
        String reason
) {
    public static PurchaseGoalMatchHistoryResponse from(PurchaseGoalMatch match) {
        return new PurchaseGoalMatchHistoryResponse(
                match.getAuctionId(), match.isMatched(), match.getSemanticScore(), match.getReason()
        );
    }
}
