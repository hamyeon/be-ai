package com.vintic.backend.bid.dto;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.bid.domain.Bid;

import java.time.LocalDateTime;

public record PlaceBidResponse(
        Long bidId,
        Long auctionId,
        Long submittedAmount,
        Long currentPrice,
        Long currentWinnerId,
        LocalDateTime bidAt
) {
    public static PlaceBidResponse of(Bid bid, Auction auction) {
        return new PlaceBidResponse(
                bid.getId(),
                auction.getId(),
                bid.getAmount(),
                auction.getCurrentPrice(),
                auction.getCurrentWinner() != null ? auction.getCurrentWinner().getId() : null,
                bid.getCreatedAt()
        );
    }

    // Idempotency replay 전용. 현재(=Auction의 최신) 상태가 아니라 최초 성공 시점의 의미를
    // 그대로 재구성해야 한다. placeManualBid()가 성공하는 순간에는 항상
    // auction.currentPrice == bid.amount, auction.currentWinner == bid.user였다는 불변식에
    // 기대어, Auction을 다시 조회하지 않고 Bid 값만으로 그 시점의 응답을 복원한다 — 그래야
    // 경매가 이후 더 진행돼도(다른 사용자의 상위 입찰 등) replay 응답이 최초 성공 응답과
    // 달라지지 않는다.
    public static PlaceBidResponse ofReplay(Bid bid) {
        return new PlaceBidResponse(
                bid.getId(),
                bid.getAuction().getId(),
                bid.getAmount(),
                bid.getAmount(),
                bid.getUser().getId(),
                bid.getCreatedAt()
        );
    }
}
