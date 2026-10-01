package com.vintic.backend.bid.service;

import com.vintic.backend.common.util.RequestHasher;

// 수동 입찰 payload의 의미 있는 값만 canonical하게 구성해 해시한다.
// JSON 직렬화 방식(공백, 필드 순서)이 아니라 값 자체로 해시를 만들어야
// 같은 의미의 요청이 다른 requestHash로 오판되지 않는다.
// 실제 해시 알고리즘/포맷은 RequestHasher로 통일한다(#41에서 AutoBid도 재사용).
final class BidRequestHash {

    private BidRequestHash() {
    }

    static String sha256(Long amount) {
        return RequestHasher.sha256("amount=" + amount);
    }
}
