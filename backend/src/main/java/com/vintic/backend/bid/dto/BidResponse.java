package com.vintic.backend.bid.dto;

import com.vintic.backend.bid.domain.Bid;
import com.vintic.backend.bid.domain.BidType;

import java.time.LocalDateTime;

public record BidResponse(
        Long bidId,
        Long bidderId,
        Long amount,
        BidType bidType,
        LocalDateTime bidAt
) {
    public static BidResponse from(Bid bid) {
        return new BidResponse(
                bid.getId(),
                bid.getUser().getId(),
                bid.getAmount(),
                bid.getBidType(),
                bid.getCreatedAt()
        );
    }
}
