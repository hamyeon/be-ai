package com.vintic.backend.backupoffer.dto;

import com.vintic.backend.backupoffer.domain.BackupOfferStatus;

import java.time.OffsetDateTime;

// FINAL contract §15.
public record BackupOfferResponse(
        Long backupOfferId,
        Long auctionId,
        BackupOfferStatus status,
        Product product,
        Long purchasePrice,
        Long shippingFee,
        Long totalAmount,
        OffsetDateTime deadline,
        OffsetDateTime serverTime
) {
    public record Product(
            Long productId,
            String name,
            String subName,
            String imageUrl
    ) {
    }
}
