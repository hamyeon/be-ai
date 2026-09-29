package com.vintic.backend.auction.dto;

public record ChangeStartPriceResponse(
        Long auctionId,
        Long startPrice
) {
}
