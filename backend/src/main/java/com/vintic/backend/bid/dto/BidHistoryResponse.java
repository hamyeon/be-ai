package com.vintic.backend.bid.dto;

import com.vintic.backend.bid.domain.Bid;
import com.vintic.backend.user.domain.User;
import org.springframework.data.domain.Page;

import java.util.List;

public record BidHistoryResponse(
        List<BidResponse> bids,
        int page,
        int size,
        boolean hasNext
) {
    public static BidHistoryResponse from(Page<Bid> bidPage, Long viewerUserId, User currentWinner, Long currentPrice) {
        List<BidResponse> bids = bidPage.getContent().stream()
                .map(bid -> BidResponse.from(bid, viewerUserId, currentWinner, currentPrice))
                .toList();
        return new BidHistoryResponse(bids, bidPage.getNumber(), bidPage.getSize(), bidPage.hasNext());
    }
}
