package com.vintic.backend.bid.dto;

import java.time.OffsetDateTime;

// FINAL contract §9 shape. 모든 필드는 AutoBid 취소 + Manual bid 반영 + Proxy counter resolution +
// 종료 연장 판정까지 전부 끝난 최종 상태를 기준으로 한다 - BidCommandService가 트랜잭션 마지막에
// 조립해서 반환하고, 그 값을 그대로 Idempotency response_snapshot에 저장한다.
public record PlaceBidResponse(
        Long bidId,
        Long submittedAmount,
        Long currentPrice,
        Long minNextBidAmount,
        String highestBidderMasked,
        boolean isHighestBidder,
        boolean autoBidCanceled,
        boolean proxyResponded,
        OffsetDateTime endsAt,
        int extensionCount
) {
}
