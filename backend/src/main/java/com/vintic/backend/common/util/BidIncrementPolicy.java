package com.vintic.backend.common.util;

// 입찰 단위는 판매자가 설정하지 않는다 - 모든 신규 경매가 이 고정값을 쓴다(기획 확정).
// Auction.bidIncrement 컬럼 자체는 경매마다 개별 값을 가질 수 있는 구조를 그대로 유지한다
// (스키마/도메인 변경 없음) - 이 정책은 "지금 그 값을 누가 채우는가"만 고정할 뿐이다.
public final class BidIncrementPolicy {

    public static final long DEFAULT_BID_INCREMENT = 5000L;

    private BidIncrementPolicy() {
    }
}
