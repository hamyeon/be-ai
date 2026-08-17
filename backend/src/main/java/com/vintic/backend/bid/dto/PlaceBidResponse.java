package com.vintic.backend.bid.dto;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.bid.domain.Bid;

import java.time.LocalDateTime;

public record PlaceBidResponse(
        Long bidId,
        Long auctionId,
        Long submittedAmount,
        Long currentPrice,
        Long currentWinnerId,
        LocalDateTime bidAt
) {
    public static PlaceBidResponse of(Bid bid, Auction auction) {
        return new PlaceBidResponse(
                bid.getId(),
                auction.getId(),
                bid.getAmount(),
                auction.getCurrentPrice(),
                auction.getCurrentWinner() != null ? auction.getCurrentWinner().getId() : null,
                bid.getCreatedAt()
        );
    }
}
