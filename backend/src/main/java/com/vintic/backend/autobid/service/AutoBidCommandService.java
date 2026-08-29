package com.vintic.backend.autobid.service;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.domain.AuctionStatus;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.autobid.domain.AutoBidSetting;
import com.vintic.backend.autobid.domain.AutoBidSettingStatus;
import com.vintic.backend.autobid.dto.AutoBidCancelResponse;
import com.vintic.backend.autobid.dto.AutoBidRegisterResponse;
import com.vintic.backend.autobid.dto.AutoBidUpdateResponse;
import com.vintic.backend.autobid.proxy.CandidateResult;
import com.vintic.backend.autobid.proxy.ProxyCandidate;
import com.vintic.backend.autobid.proxy.ProxyPriceEngine;
import com.vintic.backend.autobid.proxy.ProxyResolution;
import com.vintic.backend.autobid.proxy.ProxyResolutionInput;
import com.vintic.backend.autobid.proxy.ProxyTrigger;
import com.vintic.backend.autobid.repository.AutoBidSettingRepository;
import com.vintic.backend.bid.domain.Bid;
import com.vintic.backend.bid.domain.BidType;
import com.vintic.backend.bid.repository.BidRepository;
import com.vintic.backend.common.exception.AuctionClosedException;
import com.vintic.backend.common.exception.AuctionNotFoundException;
import com.vintic.backend.common.exception.AutoBidAlreadyExistsException;
import com.vintic.backend.common.exception.AutoBidNotFoundException;
import com.vintic.backend.common.exception.CapNotIncreasedException;
import com.vintic.backend.common.exception.CapTooLowException;
import com.vintic.backend.common.exception.PenaltyRestrictedException;
import com.vintic.backend.common.exception.SellerCannotBidException;
import com.vintic.backend.common.exception.UserNotFoundException;
import com.vintic.backend.common.util.TimePolicy;
import com.vintic.backend.user.domain.User;
import com.vintic.backend.user.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

// LIVE 경로는 ProxyPriceEngine(순수 계산)으로 실제 가격 경쟁을 계산하고, 이 서비스는 그 결과를
// 실제 Auction/AutoBidSetting/Bid에 반영하는 adapter 역할만 한다 - Proxy 가격 계산식 자체는
// 여기 없다(#42). SCHEDULED(RESERVED) 경로는 Auction 가격에 영향이 없어 여전히 Proxy를 호출하지 않는다.
@Service
public class AutoBidCommandService {

    private final AuctionRepository auctionRepository;
    private final UserRepository userRepository;
    private final AutoBidSettingRepository autoBidSettingRepository;
    private final BidRepository bidRepository;
    private final ProxyPriceEngine proxyPriceEngine;
    private final Clock clock;

    public AutoBidCommandService(
            AuctionRepository auctionRepository,
            UserRepository userRepository,
            AutoBidSettingRepository autoBidSettingRepository,
            BidRepository bidRepository,
            ProxyPriceEngine proxyPriceEngine,
            Clock clock
    ) {
        this.auctionRepository = auctionRepository;
        this.userRepository = userRepository;
        this.autoBidSettingRepository = autoBidSettingRepository;
        this.bidRepository = bidRepository;
        this.proxyPriceEngine = proxyPriceEngine;
        this.clock = clock;
    }

    // 검증 순서: 존재(Auction/User) → Penalty → Auction 상태 적격성 → Seller 제한 →
    // 기존 현재 AutoBid 존재(40908) → maxAmount>=minCapAmount(40906).
    // Penalty를 상태/판매자 체크보다 먼저 두는 것은 BidCommandService.placeManualBid()의
    // 기존 우선순위(penalty를 가장 먼저 확인)를 그대로 따른 것이다.
    // Auction은 findByIdForUpdate로 잠근다 - LIVE 등록은 Proxy resolution으로 currentPrice/
    // currentWinner를 직접 바꾸므로 RMW를 Manual Bid와 동일하게 보호해야 한다.
    @Transactional
    public AutoBidRegisterResponse createAutoBid(Long auctionId, Long userId, Long maxAmount) {
        Auction auction = auctionRepository.findByIdForUpdate(auctionId)
                .orElseThrow(() -> new AuctionNotFoundException("존재하지 않는 경매입니다. auctionId: " + auctionId));
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("존재하지 않는 사용자입니다. userId: " + userId));

        if (user.isBidRestricted(LocalDateTime.now(clock))) {
            throw new PenaltyRestrictedException("입찰 제한 기간 중인 사용자입니다. userId: " + userId);
        }
        if (auction.isClosed()) {
            throw new AuctionClosedException("이미 종료되었거나 취소된 경매입니다. auctionId: " + auctionId);
        }
        if (auction.getProduct().getSeller().isSameUser(user)) {
            throw new SellerCannotBidException("판매자는 자신의 경매에 자동입찰을 등록할 수 없습니다. auctionId: " + auctionId);
        }
        if (autoBidSettingRepository.findByAuctionIdAndUserIdAndActiveSlotTrue(auctionId, userId).isPresent()) {
            throw new AutoBidAlreadyExistsException("이미 자동입찰이 등록되어 있습니다. auctionId: " + auctionId);
        }
        Long validationMinCapAmount = auction.getMinNextBidAmount();
        if (maxAmount < validationMinCapAmount) {
            throw new CapTooLowException(
                    "자동입찰 상한가가 너무 낮습니다. 최소: " + validationMinCapAmount + ", 입력값: " + maxAmount
            );
        }

        AutoBidSetting setting = AutoBidSetting.reserve(auction, user, maxAmount);

        boolean bidOccurred = false;
        Long resultingBidAmount = null;
        boolean isHighestBidder = false;
        if (auction.getStatus() == AuctionStatus.LIVE) {
            List<AutoBidSetting> others = autoBidSettingRepository
                    .findByAuctionIdAndStatusAndUserIdNot(auctionId, AutoBidSettingStatus.ACTIVE, userId);
            ProxyResolutionInput input = buildAutoTriggerInput(auction, setting, others);
            ProxyResolution resolution = proxyPriceEngine.resolve(input);
            applyResolution(auction, setting, others, resolution);

            bidOccurred = resolution.resultingAutoBid() != null
                    && resolution.resultingAutoBid().winnerUserId().equals(userId);
            resultingBidAmount = bidOccurred ? resolution.resultingAutoBid().amount() : null;
            isHighestBidder = userId.equals(resolution.finalWinnerUserId());
        }

        // 사전 조회(위 existing-check)로 대부분의 충돌은 걸러지지만, 동시 요청 race는 여기서
        // DB unique 제약(uk_auto_bid_setting_active_slot)이 최종적으로 막는다 - 이 시점 이후로는
        // 같은 트랜잭션에서 추가 DB 작업을 하지 않고 즉시 business 예외로 변환해 던진다
        // (IdempotencyClaimService의 기존 claim-conflict 패턴과 동일).
        try {
            autoBidSettingRepository.saveAndFlush(setting);
        } catch (DataIntegrityViolationException e) {
            throw new AutoBidAlreadyExistsException("이미 자동입찰이 등록되어 있습니다. auctionId: " + auctionId);
        }

        // 응답의 currentPrice/minNextBidAmount/minCapAmount는 resolution 이후(post-state) 값이다.
        Long minCapAmount = auction.getMinNextBidAmount();
        return new AutoBidRegisterResponse(
                setting.getId(),
                auction.getId(),
                setting.getStatus(),
                setting.getMaxAmount(),
                auction.getCurrentPrice(),
                minCapAmount,
                minCapAmount,
                TimePolicy.toApiTime(auction.getStartAt()),
                bidOccurred,
                resultingBidAmount,
                isHighestBidder
        );
    }

    // 검증 순서: 현재 설정 존재(40404) → Penalty → Auction 상태(40903) →
    // minCapAmount(40906, 공통) → ACTIVE/CAP_REACHED면 상향 여부(40907).
    // 두 조건을 동시에 위반하면 40906이 우선한다(사용자 확정 사항) - minCapAmount 체크가
    // 먼저 실행되고 즉시 던지므로 코드 순서 자체가 그 우선순위를 보장한다.
    @Transactional
    public AutoBidUpdateResponse updateAutoBid(Long auctionId, Long userId, Long newMaxAmount) {
        AutoBidSetting setting = autoBidSettingRepository.findByAuctionIdAndUserIdAndActiveSlotTrue(auctionId, userId)
                .orElseThrow(() -> new AutoBidNotFoundException("등록된 자동입찰이 없습니다. auctionId: " + auctionId));

        if (setting.getUser().isBidRestricted(LocalDateTime.now(clock))) {
            throw new PenaltyRestrictedException("입찰 제한 기간 중인 사용자입니다. userId: " + userId);
        }

        // setting.getAuction()은 이 트랜잭션에서 아직 락 없이 로드됐을 수 있다 - Proxy resolution이
        // currentPrice/currentWinner를 바꾸므로 findByIdForUpdate로 다시 읽어 락을 확보한다
        // (같은 영속성 컨텍스트라 동일 관리 엔티티를 반환하며, 추가로 락만 얹힌다).
        Auction auction = auctionRepository.findByIdForUpdate(setting.getAuction().getId())
                .orElseThrow(() -> new AuctionNotFoundException("존재하지 않는 경매입니다. auctionId: " + auctionId));
        if (auction.isClosed()) {
            throw new AuctionClosedException("이미 종료되었거나 취소된 경매입니다. auctionId: " + auctionId);
        }

        Long validationMinCapAmount = auction.getMinNextBidAmount();
        if (newMaxAmount < validationMinCapAmount) {
            throw new CapTooLowException(
                    "자동입찰 상한가가 너무 낮습니다. 최소: " + validationMinCapAmount + ", 입력값: " + newMaxAmount
            );
        }
        boolean requiresIncrease = setting.getStatus() == AutoBidSettingStatus.ACTIVE
                || setting.getStatus() == AutoBidSettingStatus.CAP_REACHED;
        if (requiresIncrease && newMaxAmount <= setting.getMaxAmount()) {
            throw new CapNotIncreasedException(
                    "상한가는 현재 설정값보다 높아야 합니다. 현재: " + setting.getMaxAmount() + ", 입력값: " + newMaxAmount
            );
        }

        setting.changeMaxAmount(newMaxAmount);

        // LIVE라면 cap 상향이 실제로 다시 경쟁 가능한 수준인지 Proxy resolution으로 재판정한다.
        // CAP_REACHED가 무조건 ACTIVE로 복귀하지 않는다 - 이겨야만 복귀한다(§13 policy).
        boolean bidOccurred = false;
        Long resultingBidAmount = null;
        boolean isHighestBidder = false;
        if (auction.getStatus() == AuctionStatus.LIVE) {
            List<AutoBidSetting> others = autoBidSettingRepository
                    .findByAuctionIdAndStatusAndUserIdNot(auctionId, AutoBidSettingStatus.ACTIVE, userId);
            ProxyResolutionInput input = buildAutoTriggerInput(auction, setting, others);
            ProxyResolution resolution = proxyPriceEngine.resolve(input);
            applyResolution(auction, setting, others, resolution);

            bidOccurred = resolution.resultingAutoBid() != null
                    && resolution.resultingAutoBid().winnerUserId().equals(userId);
            resultingBidAmount = bidOccurred ? resolution.resultingAutoBid().amount() : null;
            isHighestBidder = userId.equals(resolution.finalWinnerUserId());
        }

        Long minCapAmount = auction.getMinNextBidAmount();
        return new AutoBidUpdateResponse(
                setting.getId(),
                setting.getStatus(),
                setting.getMaxAmount(),
                auction.getCurrentPrice(),
                minCapAmount,
                bidOccurred,
                resultingBidAmount,
                isHighestBidder
        );
    }

    // penalty/auction 상태를 검증하지 않는다 - "참여 중단"은 페널티 여부나 경매 상태와 무관하게
    // 항상 허용돼야 한다(계약도 §8에 40404 외의 실패 코드를 정의하지 않는다).
    @Transactional
    public AutoBidCancelResponse cancelAutoBid(Long auctionId, Long userId) {
        AutoBidSetting setting = autoBidSettingRepository.findByAuctionIdAndUserIdAndActiveSlotTrue(auctionId, userId)
                .orElseThrow(() -> new AutoBidNotFoundException("등록된 자동입찰이 없습니다. auctionId: " + auctionId));

        setting.cancel();

        return new AutoBidCancelResponse(setting.getId(), setting.getStatus(), TimePolicy.toApiTime(setting.getUpdatedAt()));
    }

    // entrant(이번 등록/수정의 당사자) + others(다른 ACTIVE 경쟁자 전체, self-heal을 위해 축소하지
    // 않고 그대로 넘긴다) 를 후보로, trigger는 AUTO(현재 currentWinner가 candidates에 없으면
    // Engine이 phantom으로 보정)로 ProxyResolutionInput을 구성한다.
    private ProxyResolutionInput buildAutoTriggerInput(Auction auction, AutoBidSetting entrant, List<AutoBidSetting> others) {
        List<ProxyCandidate> candidates = new ArrayList<>();
        candidates.add(toCandidate(entrant));
        for (AutoBidSetting other : others) {
            candidates.add(toCandidate(other));
        }
        User currentWinner = auction.getCurrentWinner();
        Long currentWinnerUserId = currentWinner == null ? null : currentWinner.getId();
        return new ProxyResolutionInput(
                auction.getCurrentPrice(),
                auction.getBidIncrement(),
                new ProxyTrigger.Auto(currentWinnerUserId),
                candidates
        );
    }

    private ProxyCandidate toCandidate(AutoBidSetting setting) {
        return new ProxyCandidate(setting.getUser().getId(), setting.getMaxAmount(), setting.getCreatedAt(), setting.getId());
    }

    // ProxyResolution(목표 상태)을 실제 Auction/AutoBidSetting/Bid에 반영한다 - 가격 계산은
    // 이미 끝난 상태이며, 여기서는 반영만 한다.
    private void applyResolution(Auction auction, AutoBidSetting entrant, List<AutoBidSetting> others, ProxyResolution resolution) {
        // 가격이 그대로여도(동률 FIRST-IN WINS) winner가 바뀔 수 있다 - priceChanged가 아니라
        // finalWinnerUserId 존재 여부로 반영 여부를 결정한다. applyProxyResult는 newPrice >=
        // currentPrice만 요구하므로 동일 가격 재적용도 안전하다.
        if (resolution.finalWinnerUserId() != null) {
            User winner = resolveUser(entrant, others, resolution.finalWinnerUserId());
            auction.applyProxyResult(winner, resolution.finalCurrentPrice());
        }
        if (resolution.resultingAutoBid() != null) {
            User bidUser = resolveUser(entrant, others, resolution.resultingAutoBid().winnerUserId());
            bidRepository.save(Bid.place(auction, bidUser, resolution.resultingAutoBid().amount(), BidType.AUTO));
        }
        for (CandidateResult result : resolution.candidateResults()) {
            AutoBidSetting target = resolveSetting(entrant, others, result.userId());
            ProxyResolutionApplier.applyStatus(target, result.status());
        }
    }

    private User resolveUser(AutoBidSetting entrant, List<AutoBidSetting> others, Long userId) {
        return resolveSetting(entrant, others, userId).getUser();
    }

    private AutoBidSetting resolveSetting(AutoBidSetting entrant, List<AutoBidSetting> others, Long userId) {
        if (entrant.getUser().getId().equals(userId)) {
            return entrant;
        }
        return others.stream()
                .filter(other -> other.getUser().getId().equals(userId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Proxy resolution 대상을 후보 목록에서 찾을 수 없습니다. userId: " + userId
                ));
    }
}
