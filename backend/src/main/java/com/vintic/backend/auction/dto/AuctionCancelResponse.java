package com.vintic.backend.auction.dto;

public record AuctionCancelResponse(
        Long auctionId,
        String status
) {
}
