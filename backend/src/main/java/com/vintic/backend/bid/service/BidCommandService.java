package com.vintic.backend.bid.service;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.autobid.domain.AutoBidSetting;
import com.vintic.backend.autobid.domain.AutoBidSettingStatus;
import com.vintic.backend.autobid.repository.AutoBidSettingRepository;
import com.vintic.backend.autobid.service.ManualBidCounterOutcome;
import com.vintic.backend.autobid.service.ProxyPriceEngine;
import com.vintic.backend.bid.domain.Bid;
import com.vintic.backend.bid.domain.BidType;
import com.vintic.backend.bid.dto.PlaceBidResponse;
import com.vintic.backend.bid.repository.BidRepository;
import com.vintic.backend.common.exception.AuctionNotFoundException;
import com.vintic.backend.common.exception.PenaltyRestrictedException;
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
public class BidCommandService {

    private final AuctionRepository auctionRepository;
    private final UserRepository userRepository;
    private final BidRepository bidRepository;
    private final AutoBidSettingRepository autoBidSettingRepository;
    private final ProxyPriceEngine proxyPriceEngine;
    private final Clock clock;

    public BidCommandService(
            AuctionRepository auctionRepository,
            UserRepository userRepository,
            BidRepository bidRepository,
            AutoBidSettingRepository autoBidSettingRepository,
            ProxyPriceEngine proxyPriceEngine,
            Clock clock
    ) {
        this.auctionRepository = auctionRepository;
        this.userRepository = userRepository;
        this.bidRepository = bidRepository;
        this.autoBidSettingRepository = autoBidSettingRepository;
        this.proxyPriceEngine = proxyPriceEngine;
        this.clock = clock;
    }

    @Transactional
    public PlaceBidResponse placeManualBid(Long auctionId, Long userId, Long amount) {
        Auction auction = auctionRepository.findByIdForUpdate(auctionId)
                .orElseThrow(() -> new AuctionNotFoundException("존재하지 않는 경매입니다. auctionId: " + auctionId));
        User bidder = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("존재하지 않는 사용자입니다. userId: " + userId));

        if (bidder.isBidRestricted(LocalDateTime.now(clock))) {
            throw new PenaltyRestrictedException("입찰 제한 기간 중인 사용자입니다. userId: " + userId);
        }

        // 기존 검증(상태/판매자/최고입찰자/최소금액)을 통과해야만 이 아래로 진행한다 - 실패하면
        // 예외가 던져지고 트랜잭션이 통째로 롤백되므로, 검증 실패 때문에 기존 AutoBid가 취소되거나
        // Proxy가 반응하는 일은 없다.
        auction.placeManualBid(bidder, amount);
        Bid bid = bidRepository.save(Bid.place(auction, bidder, amount, BidType.MANUAL));

        boolean autoBidCanceled = cancelOwnActiveAutoBidIfPresent(auctionId, userId);

        // Manual bid가 실제로 반영된 뒤(auction.currentPrice/currentWinner = 이 입찰), 다른
        // 사용자의 경쟁 AutoBid가 즉시 반격하는지 확인한다. 반격이 있으면 auction과 Bid가 그
        // 결과로 다시 갱신된다.
        ManualBidCounterOutcome counter = proxyPriceEngine.resolveAfterManualBid(auction, userId);

        User finalWinner = auction.getCurrentWinner();
        String highestBidderMasked = finalWinner == null ? null : NicknameMasker.mask(finalWinner.getNickname());
        boolean isHighestBidder = finalWinner != null && finalWinner.isSameUser(bidder);

        return new PlaceBidResponse(
                bid.getId(),
                amount,
                auction.getCurrentPrice(),
                auction.getMinNextBidAmount(),
                highestBidderMasked,
                isHighestBidder,
                autoBidCanceled,
                counter.proxyResponded(),
                TimePolicy.toApiTime(auction.getEndAt())
        );
    }

    // ACTIVE/CAP_REACHED만 취소한다 - RESERVED는 계약에 명시된 취소 대상이 아니다(§9,
    // "myState.autoBidStatus가 ACTIVE 또는 CAP_REACHED인 사용자"만 안내 대상으로 언급됨).
    private boolean cancelOwnActiveAutoBidIfPresent(Long auctionId, Long userId) {
        Optional<AutoBidSetting> setting = autoBidSettingRepository.findByAuctionIdAndUserIdAndActiveSlotTrue(auctionId, userId);
        if (setting.isEmpty()) {
            return false;
        }
        AutoBidSettingStatus status = setting.get().getStatus();
        if (status != AutoBidSettingStatus.ACTIVE && status != AutoBidSettingStatus.CAP_REACHED) {
            return false;
        }
        setting.get().cancel();
        return true;
    }
}
