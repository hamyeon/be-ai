package com.vintic.backend.autobid.service;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.autobid.domain.AutoBidSetting;
import com.vintic.backend.autobid.domain.AutoBidSettingStatus;
import com.vintic.backend.autobid.repository.AutoBidSettingRepository;
import com.vintic.backend.bid.domain.Bid;
import com.vintic.backend.bid.domain.BidType;
import com.vintic.backend.bid.repository.BidRepository;
import com.vintic.backend.user.domain.User;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

// AutoBid 등록/수정(entrant)과 Manual Bid 직후 반격(counter) 두 트리거가 같은 pairwise 가격 결정
// 규칙(§0.13 effectiveCap/FIRST-IN WINS)을 공유하도록 만든 단일 엔진이다. 호출자가 이미
// Auction을 PESSIMISTIC_WRITE로 잠근 뒤, 같은 트랜잭션 안에서 호출해야 한다 - 이 클래스 자체는
// 락을 걸지 않는다.
//
// 경쟁자 판정 불변식(#41 이후 정정):
//   - currentWinner == null                              → 경쟁자 없음
//   - currentWinner == entrant 본인                        → 자기 자신과 경쟁하지 않음
//   - currentWinner != entrant, 그 사용자의 ACTIVE AutoBid 없음 → 경쟁 ceiling = 현재 currentPrice(더 늘어나지 않음)
//   - currentWinner != entrant, 그 사용자(또는 다른 누군가)의 ACTIVE AutoBid 있음 → 그 effectiveCap이 ceiling
// 마지막 케이스는 "currentWinner가 보유한 AutoBid"로 한정하지 않고 auction 전체에서 entrant를 제외한
// ACTIVE 중 최고 effectiveCap을 찾는다 - #41(Proxy 미구현 기간)에 생성된 데이터에 여러 명이 동시에
// ACTIVE로 남아있어도(dirty data) 매 resolution마다 objectively 가장 강한 경쟁자를 기준으로 판정하므로
// 점진적으로 정상화된다(자세한 이유는 docs/api/auction-api-contract-gap.md 참고).
@Service
public class ProxyPriceEngine {

    private final AutoBidSettingRepository autoBidSettingRepository;
    private final BidRepository bidRepository;

    public ProxyPriceEngine(AutoBidSettingRepository autoBidSettingRepository, BidRepository bidRepository) {
        this.autoBidSettingRepository = autoBidSettingRepository;
        this.bidRepository = bidRepository;
    }

    public AutoBidEntrantOutcome resolveForAutoBidEntrant(Auction auction, AutoBidSetting entrant) {
        User entrantUser = entrant.getUser();
        User currentWinner = auction.getCurrentWinner();
        Long currentPrice = auction.getCurrentPrice();
        Long increment = auction.getBidIncrement();

        if (currentWinner != null && currentWinner.isSameUser(entrantUser)) {
            // 이미 자신이 최고입찰자다 - cap을 올리는 것만으로 스스로와 경쟁시키지 않는다.
            return new AutoBidEntrantOutcome(false, null, true);
        }

        Optional<AutoBidSetting> bestOther = findBestOtherActiveCompetitor(
                auction.getId(), entrantUser.getId(), currentPrice, increment
        );

        AutoBidSetting competitorSetting = bestOther.orElse(null);
        Long competitorCeiling;
        if (competitorSetting != null) {
            competitorCeiling = competitorSetting.getEffectiveCap(currentPrice, increment);
        } else if (currentWinner != null) {
            // currentWinner는 있지만 그를 뒷받침하는 ACTIVE AutoBid가 없다(manual-only) - 더 이상
            // 늘어나지 않는 고정 ceiling으로 취급한다.
            competitorCeiling = currentPrice;
        } else {
            // 진짜 경쟁자가 전혀 없다. 가격을 임의로 올리지 않는다.
            activate(entrant);
            return new AutoBidEntrantOutcome(false, null, false);
        }

        Long entrantCeiling = entrant.getEffectiveCap(currentPrice, increment);
        boolean entrantWins = competitorSetting == null
                // manual-only 상대: entrant의 ceiling은 등록 시 minCapAmount(=currentPrice+increment) 이상이
                // 보장되므로 currentPrice보다 항상 커서 수학적으로 항상 이긴다.
                || firstWins(
                        entrantCeiling, entrant.getCreatedAt(), entrant.getId(),
                        competitorCeiling, competitorSetting.getCreatedAt(), competitorSetting.getId()
                );

        if (entrantWins) {
            long newPrice = Math.min(entrantCeiling, competitorCeiling + increment);
            auction.applyProxyResult(entrantUser, newPrice);
            bidRepository.save(Bid.place(auction, entrantUser, newPrice, BidType.AUTO));
            if (competitorSetting != null) {
                competitorSetting.markCapReached();
            }
            activate(entrant);
            return new AutoBidEntrantOutcome(true, newPrice, true);
        }

        long newPrice = Math.min(competitorCeiling, entrantCeiling + increment);
        auction.applyProxyResult(competitorSetting.getUser(), newPrice);
        bidRepository.save(Bid.place(auction, competitorSetting.getUser(), newPrice, BidType.AUTO));
        if (entrant.getStatus() != AutoBidSettingStatus.CAP_REACHED) {
            entrant.markCapReached();
        }
        return new AutoBidEntrantOutcome(false, null, false);
    }

    public ManualBidCounterOutcome resolveAfterManualBid(Auction auction, Long manualBidderUserId) {
        Long currentPrice = auction.getCurrentPrice();
        Long increment = auction.getBidIncrement();

        Optional<AutoBidSetting> bestOther = findBestOtherActiveCompetitor(
                auction.getId(), manualBidderUserId, currentPrice, increment
        );
        if (bestOther.isEmpty()) {
            return new ManualBidCounterOutcome(false);
        }

        AutoBidSetting competitor = bestOther.get();
        long competitorCeiling = competitor.getEffectiveCap(currentPrice, increment);

        if (competitorCeiling <= currentPrice) {
            // 정상 상태라면 ACTIVE인 경쟁자의 ceiling은 항상 currentPrice를 넘어야 한다 - 이 분기는
            // #41(Proxy 미구현 기간)에 생성된 dirty ACTIVE row를 발견했을 때의 방어적 정상화다.
            if (competitor.getStatus() != AutoBidSettingStatus.CAP_REACHED) {
                competitor.markCapReached();
            }
            return new ManualBidCounterOutcome(false);
        }

        long newPrice = Math.min(competitorCeiling, currentPrice + increment);
        auction.applyProxyResult(competitor.getUser(), newPrice);
        bidRepository.save(Bid.place(auction, competitor.getUser(), newPrice, BidType.AUTO));
        return new ManualBidCounterOutcome(true);
    }

    private void activate(AutoBidSetting setting) {
        switch (setting.getStatus()) {
            case RESERVED -> setting.activate();
            case CAP_REACHED -> setting.reactivateAfterCapIncrease();
            case ACTIVE -> {
                // 이미 ACTIVE - 아무 것도 하지 않는다.
            }
            case CANCELED -> throw new IllegalStateException(
                    "CANCELED 상태의 AutoBidSetting은 Proxy resolution 대상이 될 수 없습니다. id: " + setting.getId()
            );
        }
    }

    private Optional<AutoBidSetting> findBestOtherActiveCompetitor(
            Long auctionId, Long excludeUserId, Long currentPrice, Long bidIncrement
    ) {
        List<AutoBidSetting> others = autoBidSettingRepository.findByAuctionIdAndStatusAndUserIdNot(
                auctionId, AutoBidSettingStatus.ACTIVE, excludeUserId
        );

        AutoBidSetting best = null;
        for (AutoBidSetting candidate : others) {
            if (best == null || firstWins(
                    candidate.getEffectiveCap(currentPrice, bidIncrement), candidate.getCreatedAt(), candidate.getId(),
                    best.getEffectiveCap(currentPrice, bidIncrement), best.getCreatedAt(), best.getId()
            )) {
                best = candidate;
            }
        }
        return Optional.ofNullable(best);
    }

    // true면 A가 B보다 우선한다: effectiveCap 내림차순 → createdAt 오름차순(먼저 등록된 쪽 우선,
    // §0.12 FIRST-IN WINS) → id 오름차순(아직 저장 전이라 id가 없는 쪽은 항상 밀림).
    private static boolean firstWins(
            Long capA, LocalDateTime createdAtA, Long idA,
            Long capB, LocalDateTime createdAtB, Long idB
    ) {
        if (!capA.equals(capB)) {
            return capA > capB;
        }
        int createdAtCompare = createdAtA.compareTo(createdAtB);
        if (createdAtCompare != 0) {
            return createdAtCompare < 0;
        }
        if (idA == null) {
            return false;
        }
        if (idB == null) {
            return true;
        }
        return idA < idB;
    }
}
