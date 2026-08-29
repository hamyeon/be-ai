package com.vintic.backend.autobid.proxy;

// 이번 resolution을 유발한 사건. 세 종류 모두 canonical spec(§0.13)에 이미 정의된 시나리오다.
public sealed interface ProxyTrigger {

    // 트리거 없음 - 경매 시작 시 RESERVED AutoBid 일괄 정산에서 쓴다(§0.13 "예약자 N명").
    // 이번 #42에서 이 트리거를 실제로 호출하는 lifecycle/scheduler는 만들지 않는다 - 계산 결과
    // shape만 미리 지원한다.
    record None() implements ProxyTrigger {
    }

    // 직접 입찰이 amount로 이미 반영된 뒤(Auction.placeManualBid 완료 후) 호출한다.
    // amount는 이미 Auction.currentPrice에 반영된 값과 같아야 한다(호출부 책임).
    record Manual(Long amount, Long bidderUserId) implements ProxyTrigger {
        public Manual {
            if (amount == null || amount <= 0) {
                throw new IllegalArgumentException("amount는 0보다 커야 합니다.");
            }
            if (bidderUserId == null) {
                throw new IllegalArgumentException("bidderUserId는 필수입니다.");
            }
        }
    }

    // AutoBid 등록/수정이 이번 resolution을 유발했다. currentWinnerUserId는 현재 Auction의
    // currentWinner다 - null이면 아무도 없음. candidates 목록에 없는 사용자라면(=AutoBid로
    // 뒷받침되지 않는 manual-only 최고입찰자) 그 사용자가 currentPrice에서 더 늘어나지 않는
    // 고정 ceiling으로 취급된다(§0.13 "AutoBid가 없으면 competitor의 최대 경쟁 가능 금액은
    // 현재 currentPrice").
    record Auto(Long currentWinnerUserId) implements ProxyTrigger {
    }
}
