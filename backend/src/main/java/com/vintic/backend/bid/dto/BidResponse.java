package com.vintic.backend.bid.dto;

import com.vintic.backend.bid.domain.Bid;
import com.vintic.backend.bid.domain.BidType;
import com.vintic.backend.common.util.NicknameMasker;
import com.vintic.backend.common.util.TimePolicy;
import com.vintic.backend.user.domain.User;

import java.time.OffsetDateTime;

// FINAL contract §3 shape. isHighest는 목록 위치나 "이 페이지에서 가장 큰 amount"로 추정하지
// 않는다 - 현재 Auction.currentWinner/currentPrice와 정확히 일치하는 단 하나의 Bid만
// isHighest=true다(같은 사용자가 여러 번 입찰했어도 마지막으로 반영된 금액인 그 한 건만).
public record BidResponse(
        Long bidId,
        String bidderMasked,
        boolean isMine,
        Long amount,
        BidType bidType,
        OffsetDateTime bidAt,
        boolean isHighest
) {
    public static BidResponse from(Bid bid, Long viewerUserId, User currentWinner, Long currentPrice) {
        boolean isMine = viewerUserId != null && bid.getUser().getId().equals(viewerUserId);
        boolean isHighest = currentWinner != null
                && bid.getUser().getId().equals(currentWinner.getId())
                && bid.getAmount().equals(currentPrice);
        return new BidResponse(
                bid.getId(),
                NicknameMasker.mask(bid.getUser().getNickname()),
                isMine,
                bid.getAmount(),
                bid.getBidType(),
                TimePolicy.toApiTime(bid.getCreatedAt()),
                isHighest
        );
    }
}
