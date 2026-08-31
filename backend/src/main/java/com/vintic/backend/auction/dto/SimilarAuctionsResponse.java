package com.vintic.backend.auction.dto;

import java.util.List;

// FINAL contract §18 shape.
public record SimilarAuctionsResponse(
        List<Item> items
) {
    public record Item(
            Long productId,
            Long auctionId,
            String brand,
            String name,
            String thumbnailUrl,
            Long price,
            int likeCount,
            boolean isLiked
    ) {
    }
}
