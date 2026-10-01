package com.vintic.backend.autobid.service;

import com.vintic.backend.common.util.RequestHasher;

// AutoBid POST/PATCH 요청 바디(maxAmount)의 canonical hash다. bid.service.BidRequestHash와
// 같은 방식(RequestHasher)을 재사용하되 payload가 다르므로 canonical 문자열은 별도로 만든다.
final class AutoBidRequestHash {

    private AutoBidRequestHash() {
    }

    static String sha256(Long maxAmount) {
        return RequestHasher.sha256("maxAmount=" + maxAmount);
    }
}
