package com.vintic.backend.bid.service;

import com.vintic.backend.auction.domain.Auction;
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

    // viewerUserId는 isMine 계산용이며 null이면(비로그인) 모든 항목이 isMine=false다.
    // isHighest는 이 조회에서 함께 가져온 Auction.currentWinner/currentPrice를 기준으로 계산한다
    // (#55 - 목록 위치/최대 amount 추정이 아니라 실제 winner 기준).
    @Transactional(readOnly = true)
    public BidHistoryResponse getBidHistory(Long auctionId, Long viewerUserId, int page, int size, String order) {
        Auction auction = auctionRepository.findByIdWithWinner(auctionId)
                .orElseThrow(() -> new AuctionNotFoundException("존재하지 않는 경매입니다. auctionId: " + auctionId));

        Pageable pageable = PageRequest.of(page, size);
        Page<Bid> bidPage = ORDER_OLDEST.equalsIgnoreCase(order)
                ? bidRepository.findByAuctionIdOrderByCreatedAtAscIdAsc(auctionId, pageable)
                : bidRepository.findByAuctionIdOrderByCreatedAtDescIdDesc(auctionId, pageable);

        return BidHistoryResponse.from(bidPage, viewerUserId, auction.getCurrentWinner(), auction.getCurrentPrice());
    }
}
