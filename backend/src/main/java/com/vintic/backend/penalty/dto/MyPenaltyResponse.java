package com.vintic.backend.penalty.dto;

import com.vintic.backend.penalty.domain.PenaltyType;

import java.time.OffsetDateTime;
import java.util.List;

// FINAL contract §14. noShowCount는 PAYMENT_EXPIRED penalty만 센다(사용자 확정, #57-2) -
// penalties.size()와 다른 값일 수 있다(FORFEITED가 이력에는 있지만 노쇼 카운트에는 없다).
public record MyPenaltyResponse(
        int noShowCount,
        boolean bidRestricted,
        OffsetDateTime bidRestrictedUntil,
        OffsetDateTime serverTime,
        List<PenaltyItem> penalties
) {
    public record PenaltyItem(
            Long penaltyId,
            PenaltyType type,
            Long auctionId,
            OffsetDateTime createdAt
    ) {
    }
}
