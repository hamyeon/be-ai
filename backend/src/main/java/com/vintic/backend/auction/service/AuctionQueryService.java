package com.vintic.backend.auction.service;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.domain.CannotBidReason;
import com.vintic.backend.auction.dto.AuctionDetailResponse;
import com.vintic.backend.auction.dto.AuctionLiveResponse;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.autobid.domain.AutoBidSetting;
import com.vintic.backend.autobid.domain.AutoBidSettingStatus;
import com.vintic.backend.autobid.dto.AutoBidRecommendationResponse;
import com.vintic.backend.autobid.repository.AutoBidSettingRepository;
import com.vintic.backend.bid.repository.BidRepository;
import com.vintic.backend.common.exception.AuctionNotFoundException;
import com.vintic.backend.common.exception.UserNotFoundException;
import com.vintic.backend.common.util.NicknameMasker;
import com.vintic.backend.common.util.TimePolicy;
import com.vintic.backend.user.domain.User;
import com.vintic.backend.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class AuctionQueryService {

    private final AuctionRepository auctionRepository;
    private final BidRepository bidRepository;
    private final UserRepository userRepository;
    private final AutoBidSettingRepository autoBidSettingRepository;
    private final Clock clock;

    public AuctionQueryService(
            AuctionRepository auctionRepository,
            BidRepository bidRepository,
            UserRepository userRepository,
            AutoBidSettingRepository autoBidSettingRepository,
            Clock clock
    ) {
        this.auctionRepository = auctionRepository;
        this.bidRepository = bidRepository;
        this.userRepository = userRepository;
        this.autoBidSettingRepository = autoBidSettingRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AuctionDetailResponse getAuctionDetail(Long auctionId) {
        Auction auction = auctionRepository.findById(auctionId)
                .orElseThrow(() -> new AuctionNotFoundException("존재하지 않는 경매입니다. auctionId: " + auctionId));
        long bidCount = bidRepository.countByAuctionId(auctionId);
        return AuctionDetailResponse.of(auction, bidCount);
    }

    @Transactional(readOnly = true)
    public AuctionLiveResponse getLiveView(Long auctionId, Long userId) {
        Auction auction = auctionRepository.findByIdWithProductAndWinner(auctionId)
                .orElseThrow(() -> new AuctionNotFoundException("존재하지 않는 경매입니다. auctionId: " + auctionId));
        User currentUser = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("존재하지 않는 사용자입니다. userId: " + userId));

        Long minNextBidAmount = auction.getMinNextBidAmount();
        User winner = auction.getCurrentWinner();
        String highestBidderMasked = winner == null ? null : NicknameMasker.mask(winner.getNickname());
        boolean isMine = winner != null && winner.isSameUser(currentUser);

        CannotBidReason cannotBidReason = auction.determineCannotBidReason(currentUser, LocalDateTime.now(clock));
        boolean canBid = cannotBidReason == null;
        LocalDateTime bidRestrictedUntil = cannotBidReason == CannotBidReason.PENALTY_RESTRICTED
                ? currentUser.getBidRestrictedUntil()
                : null;

        // #41: activeSlot=true인 "현재 설정"만 조회하므로(CANCELED는 항상 activeSlot=null이라
        // 이 쿼리에 아예 안 걸림) 여기서 status로 다시 CANCELED를 걸러낼 필요가 없다.
        AutoBidSettingStatus myAutoBidStatus = null;
        Long myCap = null;
        Optional<AutoBidSetting> setting = autoBidSettingRepository.findByAuctionIdAndUserIdAndActiveSlotTrue(auctionId, userId);
        if (setting.isPresent()) {
            myAutoBidStatus = setting.get().getStatus();
            myCap = setting.get().getMaxAmount();
        }

        return new AuctionLiveResponse(
                auction.getId(),
                auction.getStatus(),
                auction.getCurrentPrice(),
                minNextBidAmount,
                auction.getBidIncrement(),
                highestBidderMasked,
                isMine,
                canBid,
                cannotBidReason,
                TimePolicy.toApiTime(bidRestrictedUntil),
                TimePolicy.toApiTime(auction.getEndAt()),
                TimePolicy.toApiTime(LocalDateTime.now(clock)),
                myAutoBidStatus,
                myCap,
                minNextBidAmount
        );
    }

    @Transactional(readOnly = true)
    public AutoBidRecommendationResponse getAutoBidRecommendation(Long auctionId) {
        Auction auction = auctionRepository.findById(auctionId)
                .orElseThrow(() -> new AuctionNotFoundException("존재하지 않는 경매입니다. auctionId: " + auctionId));
        Long minCapAmount = auction.getMinNextBidAmount();

        return new AutoBidRecommendationResponse(
                auction.getId(),
                minCapAmount,
                auction.getCurrentPrice(),
                minCapAmount,
                auction.getBidIncrement()
        );
    }
}
