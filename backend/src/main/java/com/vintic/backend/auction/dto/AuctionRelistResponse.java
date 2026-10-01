package com.vintic.backend.auction.dto;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.common.util.TimePolicy;

import java.time.OffsetDateTime;

public record AuctionRelistResponse(
        Long auctionId,
        Long previousAuctionId,
        Long productId,
        Long startPrice,
        Long bidIncrement,
        OffsetDateTime startAt,
        OffsetDateTime endAt,
        String status
) {
    public static AuctionRelistResponse from(Auction auction, Long previousAuctionId) {
        return new AuctionRelistResponse(
                auction.getId(),
                previousAuctionId,
                auction.getProduct().getId(),
                auction.getStartPrice(),
                auction.getBidIncrement(),
                TimePolicy.toApiTime(auction.getStartAt()),
                TimePolicy.toApiTime(auction.getEndAt()),
                auction.getStatus().name()
        );
    }
}
