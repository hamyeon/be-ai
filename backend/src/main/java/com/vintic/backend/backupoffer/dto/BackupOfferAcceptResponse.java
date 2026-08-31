package com.vintic.backend.backupoffer.dto;

import com.vintic.backend.backupoffer.domain.BackupOfferStatus;

import java.time.OffsetDateTime;

// FINAL contract §16. status는 항상 ACCEPTED 고정.
public record BackupOfferAcceptResponse(
        Long backupOfferId,
        BackupOfferStatus status,
        Long orderId,
        Long totalAmount,
        OffsetDateTime paymentDeadline
) {
}
