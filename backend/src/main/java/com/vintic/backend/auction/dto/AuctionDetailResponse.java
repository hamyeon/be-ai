package com.vintic.backend.auction.dto;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.domain.AuctionStatus;

import java.time.LocalDateTime;

public record AuctionDetailResponse(
        Long id,
        Long productId,
        Long sellerId,
        Long currentWinnerId,
        Long startPrice,
        Long currentPrice,
        Long bidIncrement,
        LocalDateTime startAt,
        LocalDateTime endAt,
        AuctionStatus status,
        long bidCount
) {
    public static AuctionDetailResponse of(Auction auction, long bidCount) {
        return new AuctionDetailResponse(
                auction.getId(),
                auction.getProduct().getId(),
                auction.getProduct().getSeller().getId(),
                auction.getCurrentWinner() != null ? auction.getCurrentWinner().getId() : null,
                auction.getStartPrice(),
                auction.getCurrentPrice(),
                auction.getBidIncrement(),
                auction.getStartAt(),
                auction.getEndAt(),
                auction.getStatus(),
                bidCount
        );
    }
}
