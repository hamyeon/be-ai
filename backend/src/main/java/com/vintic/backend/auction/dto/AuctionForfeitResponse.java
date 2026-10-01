package com.vintic.backend.auction.dto;

import com.vintic.backend.auction.domain.AuctionResult;

// FINAL contract §11. result는 항상 FORFEITED 고정이다.
public record AuctionForfeitResponse(
        Long auctionId,
        AuctionResult result
) {
}
