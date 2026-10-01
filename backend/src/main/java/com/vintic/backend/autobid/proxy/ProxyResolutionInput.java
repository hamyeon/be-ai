package com.vintic.backend.autobid.proxy;

import java.util.List;

// ProxyPriceEngine.resolve()의 입력. currentPrice/bidIncrement는 이 resolution 시작 시점(=이번
// 트리거가 반영되기 직전, MANUAL의 경우는 Auction.placeManualBid 반영 "이후")의 Auction 값이다.
// candidates는 CANCELED가 아닌 모든 경쟁 후보를 담는다 - 호출부가 이미 걸러서 넘긴다(엔진은
// 다시 필터링하지 않는다, 단 목록이 비어있는 것은 정상 입력이다).
public record ProxyResolutionInput(
        Long currentPrice,
        Long bidIncrement,
        ProxyTrigger trigger,
        List<ProxyCandidate> candidates
) {
    public ProxyResolutionInput {
        if (currentPrice == null || currentPrice <= 0) {
            throw new IllegalArgumentException("currentPrice는 0보다 커야 합니다.");
        }
        if (bidIncrement == null || bidIncrement <= 0) {
            throw new IllegalArgumentException("bidIncrement는 0보다 커야 합니다.");
        }
        if (trigger == null) {
            throw new IllegalArgumentException("trigger는 필수입니다.");
        }
        if (candidates == null) {
            throw new IllegalArgumentException("candidates는 필수입니다(없으면 빈 리스트).");
        }
    }
}
