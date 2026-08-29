package com.vintic.backend.autobid.proxy;

// 이번 resolution으로 실제로 만들어져야 하는 단 하나의 AUTO Bid다(A→B→A→B 같은 중간 경합은
// 저장하지 않는다 - 최종 결과만). price와 winner 둘 다 그대로면 null이다 - 단, 동률(가격은
// 그대로지만 FIRST-IN WINS로 winner만 바뀌는 경우)에는 priceChanged=false여도 non-null이다
// (winner가 실제로 반격했다는 사실 자체를 기록해야 하므로).
public record ResultingAutoBid(
        Long winnerUserId,
        Long amount
) {
}
