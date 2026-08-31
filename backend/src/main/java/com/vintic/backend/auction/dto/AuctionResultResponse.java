package com.vintic.backend.auction.dto;

import com.vintic.backend.auction.domain.AuctionResult;

import java.time.OffsetDateTime;

// FINAL contract §10 shape. rank/finalPrice/myLastBidAmount/shippingFee/totalAmount/
// paymentDeadline/orderId/backupOfferId는 계약상 optional(X) - result에 따라 null이다.
// backupOfferId는 #56-1 범위에 BackupOffer 도메인이 없어 항상 null이다(#56-2에서 채워진다).
public record AuctionResultResponse(
        Long auctionId,
        AuctionResult result,
        Product product,
        Integer rank,
        Long finalPrice,
        Long myLastBidAmount,
        Long shippingFee,
        Long totalAmount,
        OffsetDateTime paymentDeadline,
        OffsetDateTime serverTime,
        Long orderId,
        Long backupOfferId,
        boolean backupEligible
) {
    public record Product(
            Long productId,
            String name,
            String subName,
            String imageUrl
    ) {
    }
}
