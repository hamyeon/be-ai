package com.vintic.backend.auction.service;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.dto.AuctionDetailResponse;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.bid.repository.BidRepository;
import com.vintic.backend.common.exception.AuctionNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuctionQueryService {

    private final AuctionRepository auctionRepository;
    private final BidRepository bidRepository;

    public AuctionQueryService(AuctionRepository auctionRepository, BidRepository bidRepository) {
        this.auctionRepository = auctionRepository;
        this.bidRepository = bidRepository;
    }

    @Transactional(readOnly = true)
    public AuctionDetailResponse getAuctionDetail(Long auctionId) {
        Auction auction = auctionRepository.findById(auctionId)
                .orElseThrow(() -> new AuctionNotFoundException("존재하지 않는 경매입니다. auctionId: " + auctionId));
        long bidCount = bidRepository.countByAuctionId(auctionId);
        return AuctionDetailResponse.of(auction, bidCount);
    }
}
