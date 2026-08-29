package com.vintic.backend.autobid.service;

// ProxyPriceEngine.resolveAfterManualBid()의 결과. BidCommandService가 PlaceBidResponse의
// proxyResponded/currentPrice(재조회 없이 auction 객체가 이미 갱신됨)에 반영한다.
public record ManualBidCounterOutcome(
        boolean proxyResponded
) {
}
