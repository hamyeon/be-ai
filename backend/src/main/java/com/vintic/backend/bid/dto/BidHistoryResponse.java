package com.vintic.backend.bid.dto;

import com.vintic.backend.bid.domain.Bid;
import org.springframework.data.domain.Page;

import java.util.List;

public record BidHistoryResponse(
        List<BidResponse> bids,
        int page,
        int size,
        boolean hasNext
) {
    public static BidHistoryResponse from(Page<Bid> bidPage) {
        List<BidResponse> bids = bidPage.getContent().stream()
                .map(BidResponse::from)
                .toList();
        return new BidHistoryResponse(bids, bidPage.getNumber(), bidPage.getSize(), bidPage.hasNext());
    }
}
