package com.vintic.backend.auction.service;

// Day12: API 서버 2대에서 AuctionEndScheduler가 같은 경매를 동시에 종료 시도한 실험 결과,
// 정합성(실제 상태 변경/주문 생성은 경매당 1회)은 지켜졌지만 관측성이 깨졌다 - endIfDue()가
// void라서 늦게 lock을 얻어 "이미 ENDED라 아무것도 안 한" 호출도 예외가 없으니 Scheduler가
// success로 셌고, 두 인스턴스가 각자 success=3을 남겨 실제로는 3건 종료인데 로그만 보면 6건
// 종료로 오해하게 됐다. endIfDue()가 실제로 한 일을 이 enum으로 반환해 이 착시를 없앤다.
public enum AuctionEndOutcome {
    ENDED,
    NOT_LIVE,
    NOT_DUE,
    NOT_FOUND
}
