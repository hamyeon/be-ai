package com.vintic.backend.bid.service;

import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.bid.domain.Bid;
import com.vintic.backend.bid.dto.BidHistoryResponse;
import com.vintic.backend.bid.repository.BidRepository;
import com.vintic.backend.common.exception.AuctionNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BidQueryService {

    private static final String ORDER_OLDEST = "oldest";

    private final BidRepository bidRepository;
    private final AuctionRepository auctionRepository;

    public BidQueryService(BidRepository bidRepository, AuctionRepository auctionRepository) {
        this.bidRepository = bidRepository;
        this.auctionRepository = auctionRepository;
    }

    @Transactional(readOnly = true)
    public BidHistoryResponse getBidHistory(Long auctionId, int page, int size, String order) {
        if (!auctionRepository.existsById(auctionId)) {
            throw new AuctionNotFoundException("존재하지 않는 경매입니다. auctionId: " + auctionId);
        }

        Pageable pageable = PageRequest.of(page, size);
        Page<Bid> bidPage = ORDER_OLDEST.equalsIgnoreCase(order)
                ? bidRepository.findByAuctionIdOrderByCreatedAtAscIdAsc(auctionId, pageable)
                : bidRepository.findByAuctionIdOrderByCreatedAtDescIdDesc(auctionId, pageable);

        return BidHistoryResponse.from(bidPage);
    }
}
