package com.vintic.backend.bid.service;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.bid.domain.Bid;
import com.vintic.backend.bid.domain.BidType;
import com.vintic.backend.bid.dto.PlaceBidResponse;
import com.vintic.backend.bid.repository.BidRepository;
import com.vintic.backend.common.exception.AuctionNotFoundException;
import com.vintic.backend.common.exception.PenaltyRestrictedException;
import com.vintic.backend.common.exception.UserNotFoundException;
import com.vintic.backend.user.domain.User;
import com.vintic.backend.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class BidCommandService {

    private final AuctionRepository auctionRepository;
    private final UserRepository userRepository;
    private final BidRepository bidRepository;

    public BidCommandService(
            AuctionRepository auctionRepository,
            UserRepository userRepository,
            BidRepository bidRepository
    ) {
        this.auctionRepository = auctionRepository;
        this.userRepository = userRepository;
        this.bidRepository = bidRepository;
    }

    @Transactional
    public PlaceBidResponse placeManualBid(Long auctionId, Long userId, Long amount) {
        Auction auction = auctionRepository.findById(auctionId)
                .orElseThrow(() -> new AuctionNotFoundException("존재하지 않는 경매입니다. auctionId: " + auctionId));
        User bidder = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("존재하지 않는 사용자입니다. userId: " + userId));

        if (bidder.isBidRestricted(LocalDateTime.now())) {
            throw new PenaltyRestrictedException("입찰 제한 기간 중인 사용자입니다. userId: " + userId);
        }

        auction.placeManualBid(bidder, amount);
        Bid bid = bidRepository.save(Bid.place(auction, bidder, amount, BidType.MANUAL));

        return PlaceBidResponse.of(bid, auction);
    }
}
