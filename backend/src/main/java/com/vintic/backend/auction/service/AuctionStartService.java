package com.vintic.backend.auction.service;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.domain.AuctionStatus;
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
import com.vintic.backend.bid.repository.BidRepository;
import com.vintic.backend.user.domain.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

// #73-1: SCHEDULED -> LIVE lifecycle 전환 + 시작 시점 RESERVED AutoBidSetting 일괄 정산.
// lock 순서: Auction FOR UPDATE -> AutoBidSetting(RESERVED) FOR UPDATE -> validation ->
// state transition -> Proxy resolution 반영 -> commit. Auction을 먼저 잠그는 원칙은
// AuctionForfeitService/AutoBidCommandService와 동일하다(#45/#46이 확립한 "Auction 먼저"
// lock ordering을 그대로 따른다 - 새 순서를 만들지 않는다).
//
// ProxyTrigger.None은 #42에서 이미 "이 트리거를 실제로 호출하는 lifecycle/scheduler는 아직
// 없다"고 명시하며 정확히 이 시나리오(경매 시작 시 RESERVED 일괄 정산)를 위해 계산 shape만
// 먼저 만들어 둔 것이다 - 이 서비스가 그 첫 호출부다. 가격/승자/CAP_REACHED 판정 공식은
// 전부 ProxyPriceEngine에 있고, 여기서는 새로 만들지 않는다(scheduler 전용 bidding rule 없음).
@Service
public class AuctionStartService {

    private final AuctionRepository auctionRepository;
    private final AutoBidSettingRepository autoBidSettingRepository;
    private final BidRepository bidRepository;
    private final ProxyPriceEngine proxyPriceEngine;
    private final Clock clock;

    public AuctionStartService(
            AuctionRepository auctionRepository,
            AutoBidSettingRepository autoBidSettingRepository,
            BidRepository bidRepository,
            ProxyPriceEngine proxyPriceEngine,
            Clock clock
    ) {
        this.auctionRepository = auctionRepository;
        this.autoBidSettingRepository = autoBidSettingRepository;
        this.bidRepository = bidRepository;
        this.proxyPriceEngine = proxyPriceEngine;
        this.clock = clock;
    }

    // 대상: SCHEDULED && startAt <= now. 락 이후 상태/startAt을 재검증해 이미 LIVE/ENDED/
    // CANCELED이거나 아직 startAt 전이면 조용히 건너뛴다(OrderExpirationService.expireIfDue()와
    // 동일한 원칙 - 스케줄러가 non-locking으로 고른 후보가 stale할 수 있다는 전제, 재실행 시
    // 중복 처리 없음의 핵심 방어선). Auction.start() 자체가 SCHEDULED가 아니면 예외를 던지므로
    // 여기서 먼저 걸러 그 가드에 도달하지 않게 한다(cancel()/pay()/expire()와 동일한 패턴 -
    // 서비스가 상태를 먼저 확인하고, 도메인 메서드는 프로그래밍 오류 가드로만 쓰인다).
    @Transactional
    public void startIfDue(Long auctionId) {
        Auction auction = auctionRepository.findByIdForUpdate(auctionId).orElse(null);
        if (auction == null) {
            return;
        }

        LocalDateTime now = LocalDateTime.now(clock);
        if (auction.getStatus() != AuctionStatus.SCHEDULED || auction.getStartAt().isAfter(now)) {
            return;
        }

        auction.start();

        List<AutoBidSetting> reserved = autoBidSettingRepository
                .findByAuctionIdAndStatusForUpdate(auctionId, AutoBidSettingStatus.RESERVED);
        if (reserved.isEmpty()) {
            return;
        }

        ProxyResolutionInput input = new ProxyResolutionInput(
                auction.getCurrentPrice(),
                auction.getBidIncrement(),
                new ProxyTrigger.None(),
                toCandidates(reserved)
        );
        ProxyResolution resolution = proxyPriceEngine.resolve(input);
        applyResolution(auction, reserved, resolution);
    }

    private List<ProxyCandidate> toCandidates(List<AutoBidSetting> settings) {
        List<ProxyCandidate> candidates = new ArrayList<>();
        for (AutoBidSetting setting : settings) {
            candidates.add(new ProxyCandidate(setting.getUser().getId(), setting.getMaxAmount(), setting.getCreatedAt(), setting.getId()));
        }
        return candidates;
    }

    // AutoBidCommandService.applyResolution()과 동일한 구조다 - 여기엔 "entrant"가 없고 RESERVED
    // 전체가 곧 candidate pool이라 그 구분만 뺐다. 가격/Bid/상태 반영 방식 자체는 그대로 재사용한다.
    private void applyResolution(Auction auction, List<AutoBidSetting> reserved, ProxyResolution resolution) {
        if (resolution.finalWinnerUserId() != null) {
            User winner = resolveUser(reserved, resolution.finalWinnerUserId());
            auction.applyProxyResult(winner, resolution.finalCurrentPrice());
        }
        if (resolution.resultingAutoBid() != null) {
            User bidUser = resolveUser(reserved, resolution.resultingAutoBid().winnerUserId());
            bidRepository.save(Bid.place(auction, bidUser, resolution.resultingAutoBid().amount(), BidType.AUTO));
        }
        for (CandidateResult result : resolution.candidateResults()) {
            AutoBidSetting target = resolveSetting(reserved, result.userId());
            ProxyResolutionApplier.applyStatus(target, result.status());
        }
    }

    private User resolveUser(List<AutoBidSetting> settings, Long userId) {
        return resolveSetting(settings, userId).getUser();
    }

    private AutoBidSetting resolveSetting(List<AutoBidSetting> settings, Long userId) {
        return settings.stream()
                .filter(setting -> setting.getUser().getId().equals(userId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Proxy resolution 대상을 후보 목록에서 찾을 수 없습니다. userId: " + userId
                ));
    }
}
