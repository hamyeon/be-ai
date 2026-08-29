package com.vintic.backend.autobid.service;

// ProxyPriceEngine.resolveForAutoBidEntrant()의 결과. AutoBidCommandService가 이 값을 그대로
// POST/PATCH 응답의 bidOccurred/resultingBidAmount/isHighestBidder에 매핑한다.
public record AutoBidEntrantOutcome(
        boolean bidOccurred,
        Long resultingBidAmount,
        boolean isHighestBidder
) {
}
