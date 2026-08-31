package com.vintic.backend.bid.service;

import com.vintic.backend.auction.audit.AuctionPriceAuditRecorder;
import com.vintic.backend.auction.audit.PriceAuditTrigger;
import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.autobid.domain.AutoBidSetting;
import com.vintic.backend.autobid.domain.AutoBidSettingStatus;
import com.vintic.backend.autobid.proxy.CandidateResult;
import com.vintic.backend.autobid.proxy.ProxyCandidate;
import com.vintic.backend.autobid.proxy.ProxyPriceEngine;
import com.vintic.backend.autobid.proxy.ProxyResolution;
import com.vintic.backend.autobid.proxy.ProxyResolutionInput;
import com.vintic.backend.autobid.proxy.ProxyTrigger;
import com.vintic.backend.autobid.repository.AutoBidSettingRepository;
import com.vintic.backend.autobid.service.ProxyResolutionApplier;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class BidCommandService {

    private final AuctionRepository auctionRepository;
    private final UserRepository userRepository;
    private final BidRepository bidRepository;
    private final AutoBidSettingRepository autoBidSettingRepository;
    private final ProxyPriceEngine proxyPriceEngine;
    private final AuctionPriceAuditRecorder auctionPriceAuditRecorder;
    private final Clock clock;

    public BidCommandService(
            AuctionRepository auctionRepository,
            UserRepository userRepository,
            BidRepository bidRepository,
            AutoBidSettingRepository autoBidSettingRepository,
            ProxyPriceEngine proxyPriceEngine,
            AuctionPriceAuditRecorder auctionPriceAuditRecorder,
            Clock clock
    ) {
        this.auctionRepository = auctionRepository;
        this.userRepository = userRepository;
        this.bidRepository = bidRepository;
        this.autoBidSettingRepository = autoBidSettingRepository;
        this.proxyPriceEngine = proxyPriceEngine;
        this.auctionPriceAuditRecorder = auctionPriceAuditRecorder;
        this.clock = clock;
    }

    // 기존(#30~#44) 호출부/테스트가 그대로 쓰는 진입점이다 - idempotency 경로가 아닌 직접 호출은
    // audit의 idempotencyId를 null로 남긴다(SYSTEM_OPEN처럼 키가 없는 경우와 동일하게 nullable).
    @Transactional
    public PlaceBidResponse placeManualBid(Long auctionId, Long userId, Long amount) {
        return placeManualBid(auctionId, userId, amount, null);
    }

    // #45: idempotencyId는 ManualBidService가 IdempotencyClaimService의 claim row PK를 그대로
    // 넘겨주는 참조값이다 - 여기서 그 값의 유효성을 재검증하지 않는다(호출자 책임).
    @Transactional
    public PlaceBidResponse placeManualBid(Long auctionId, Long userId, Long amount, Long idempotencyId) {
        Auction auction = auctionRepository.findByIdForUpdate(auctionId)
                .orElseThrow(() -> new AuctionNotFoundException("존재하지 않는 경매입니다. auctionId: " + auctionId));
        User bidder = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("존재하지 않는 사용자입니다. userId: " + userId));

        if (bidder.isBidRestricted(LocalDateTime.now(clock))) {
            throw new PenaltyRestrictedException("입찰 제한 기간 중인 사용자입니다. userId: " + userId);
        }

        // audit의 beforePrice/beforeWinner 기준점이다 - 이 command가 Auction을 건드리기 전 상태를
        // 여기서 캡처해둬야 한다(placeManualBid() 호출 이후에는 이미 mutate된 값만 남는다).
        Long beforePrice = auction.getCurrentPrice();
        User beforeWinner = auction.getCurrentWinner();
        Long beforeWinnerId = beforeWinner == null ? null : beforeWinner.getId();

        // 기존 검증(상태/판매자/최고입찰자/최소금액)을 통과해야만 이 아래로 진행한다 - 실패하면
        // 예외가 던져지고 트랜잭션이 통째로 롤백되므로, 검증 실패 때문에 기존 AutoBid가 취소되거나
        // Proxy가 반응하는 일은 없다.
        auction.placeManualBid(bidder, amount);
        Bid bid = bidRepository.save(Bid.place(auction, bidder, amount, BidType.MANUAL));

        boolean autoBidCanceled = cancelOwnActiveAutoBidIfPresent(auctionId, userId);

        // Manual bid가 실제로 반영된 뒤(auction.currentPrice/currentWinner = 이 입찰), 다른
        // 사용자의 경쟁 AutoBid가 즉시 반격하는지 확인한다. 반격이 있으면 auction과 Bid가 그
        // 결과로 다시 갱신된다.
        List<AutoBidSetting> others = autoBidSettingRepository
                .findByAuctionIdAndStatusAndUserIdNot(auctionId, AutoBidSettingStatus.ACTIVE, userId);
        ProxyResolutionInput input = new ProxyResolutionInput(
                auction.getCurrentPrice(),
                auction.getBidIncrement(),
                new ProxyTrigger.Manual(amount, userId),
                toCandidates(others)
        );
        ProxyResolution resolution = proxyPriceEngine.resolve(input);
        applyResolution(auction, bidder, others, resolution);

        // 종료 연장(FINAL contract §0.13/§9): Manual Bid 성공은 항상 실제 MANUAL Bid를 만들어내므로,
        // 그 뒤 Proxy 반격이 있었는지와 무관하게 이 사용자 command 기준 최대 1회만 판정한다.
        auction.maybeExtend(LocalDateTime.now(clock));

        // #45: audit도 이 트랜잭션 안에서 커맨드당 최대 1회만 기록한다 - Manual Bid 성공은 항상
        // beforePrice(command 시작 전)보다 최종 가격이 높으므로 사실상 항상 기록되지만, 판단
        // 자체는 recordIfChanged의 공통 규칙(priceChanged || winnerChanged)에 맡긴다.
        auctionPriceAuditRecorder.recordIfChanged(
                auction, PriceAuditTrigger.MANUAL_BID, userId, beforePrice, beforeWinnerId,
                resolution.priceChanged(), idempotencyId
        );

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
                resolution.proxyResponded(),
                TimePolicy.toApiTime(auction.getEndAt()),
                auction.getExtensionCount()
        );
    }

    private List<ProxyCandidate> toCandidates(List<AutoBidSetting> settings) {
        List<ProxyCandidate> candidates = new ArrayList<>();
        for (AutoBidSetting setting : settings) {
            candidates.add(new ProxyCandidate(setting.getUser().getId(), setting.getMaxAmount(), setting.getCreatedAt(), setting.getId()));
        }
        return candidates;
    }

    // ProxyResolution(목표 상태)을 실제 Auction/AutoBidSetting/Bid에 반영한다. manual bidder는
    // candidates pool에 AutoBidSetting으로 들어있지 않다(방금 자신의 AutoBid는 취소됐거나 애초에
    // 없었다) - Manual 트리거의 phantom(= 방금 반영된 manual bid 그 자체)이 그 자리를 대신한다.
    private void applyResolution(Auction auction, User bidder, List<AutoBidSetting> others, ProxyResolution resolution) {
        // 가격이 그대로여도(동률 FIRST-IN WINS) winner가 바뀔 수 있다 - priceChanged가 아니라
        // finalWinnerUserId 존재 여부로 반영 여부를 결정한다. applyProxyResult는 newPrice >=
        // currentPrice만 요구하므로 동일 가격 재적용도 안전하다.
        if (resolution.finalWinnerUserId() != null) {
            User winner = resolveUser(bidder, others, resolution.finalWinnerUserId());
            auction.applyProxyResult(winner, resolution.finalCurrentPrice());
        }
        if (resolution.resultingAutoBid() != null) {
            User bidUser = resolveUser(bidder, others, resolution.resultingAutoBid().winnerUserId());
            bidRepository.save(Bid.place(auction, bidUser, resolution.resultingAutoBid().amount(), BidType.AUTO));
        }
        for (CandidateResult result : resolution.candidateResults()) {
            AutoBidSetting target = resolveSetting(others, result.userId());
            ProxyResolutionApplier.applyStatus(target, result.status());
        }
    }

    private User resolveUser(User bidder, List<AutoBidSetting> others, Long userId) {
        if (bidder.getId().equals(userId)) {
            return bidder;
        }
        return resolveSetting(others, userId).getUser();
    }

    private AutoBidSetting resolveSetting(List<AutoBidSetting> others, Long userId) {
        return others.stream()
                .filter(other -> other.getUser().getId().equals(userId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Proxy resolution 대상을 후보 목록에서 찾을 수 없습니다. userId: " + userId
                ));
    }

    // ACTIVE/CAP_REACHED만 취소한다 - RESERVED는 계약에 명시된 취소 대상이 아니다(§9,
    // "myState.autoBidStatus가 ACTIVE 또는 CAP_REACHED인 사용자"만 안내 대상으로 언급됨).
    //
    // #46 follow-up: locking current read를 쓴다 - Auction 락은 이 메서드 호출 시점에 이미
    // placeManualBid()에서 잡혀 있으므로 lock ordering 위반 없이 여기서 own-setting을 잠글 수
    // 있다. non-locking read였다면, 같은 사용자가 동시에 다른 경로(예: 다른 탭의 AutoBid CREATE)로
    // 방금 커밋한 ACTIVE 설정을 REPEATABLE READ read view 때문에 놓쳐 autoBidCanceled=false로
    // 잘못 응답하고 실제로는 취소돼야 할 AutoBid가 남을 수 있었다(§9 정책 위반).
    private boolean cancelOwnActiveAutoBidIfPresent(Long auctionId, Long userId) {
        Optional<AutoBidSetting> setting = autoBidSettingRepository.findCurrentByAuctionIdAndUserIdForUpdate(auctionId, userId);
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
