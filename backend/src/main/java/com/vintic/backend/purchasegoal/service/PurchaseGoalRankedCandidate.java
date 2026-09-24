package com.vintic.backend.purchasegoal.service;

import com.vintic.backend.ai.purchase.match.MatchResult;
import com.vintic.backend.ai.purchase.price.PriceEstimate;
import com.vintic.backend.auction.domain.Auction;

// PurchaseGoalCandidateRanker의 내부 결과 - Day 5(실제 AutoBid 등록)가 재확인할 때 쓸 문맥이다.
// 이 record의 값들은 Day 4 시점의 스냅샷이다 - Day 5는 등록 직전에 auction 상태·현재가·시세·cap·
// 기존 AutoBid를 다시 조회/계산해서 이 스냅샷이 아직 유효한지 확인해야 한다(그대로 신뢰하지 않는다).
// 공개 API 응답이 아니다.
public record PurchaseGoalRankedCandidate(
        Auction auction,
        PriceEstimate priceEstimate,
        MatchResult matchResult,
        long cap
) {
}
