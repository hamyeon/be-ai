package com.vintic.backend.autobid.service;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.autobid.domain.AutoBidSetting;
import com.vintic.backend.autobid.domain.AutoBidSettingStatus;
import com.vintic.backend.autobid.repository.AutoBidSettingRepository;
import com.vintic.backend.bid.domain.BidType;
import com.vintic.backend.bid.repository.BidRepository;
import com.vintic.backend.product.domain.Product;
import com.vintic.backend.user.domain.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(ProxyPriceEngine.class)
class ProxyPriceEngineTest {

    @Autowired
    private ProxyPriceEngine proxyPriceEngine;

    @Autowired
    private AuctionRepository auctionRepository;

    @Autowired
    private AutoBidSettingRepository autoBidSettingRepository;

    @Autowired
    private BidRepository bidRepository;

    @Autowired
    private EntityManager entityManager;

    private User persistUser(String email) {
        User user = User.register(email, email, null);
        entityManager.persist(user);
        return user;
    }

    private Auction persistLiveAuction(User seller) {
        Product product = new Product(
                seller,
                List.of("https://example.com/a.jpg"),
                "Nike", "Dunk Low", "Panda", 270, "B", "PARTIAL",
                300000, 350000, "285,000원 ~ 315,000원", 290000, "사유", "설명"
        );
        entityManager.persist(product);

        Auction auction = Auction.schedule(
                product, 105000L, 5000L, LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(1)
        );
        auction.start();
        entityManager.persist(auction);
        return auction;
    }

    private AutoBidSetting persistActiveAutoBid(Auction auction, User user, Long maxAmount) {
        AutoBidSetting setting = AutoBidSetting.reserve(auction, user, maxAmount);
        setting.activate();
        return autoBidSettingRepository.saveAndFlush(setting);
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void currentWinner가_없으면_경쟁없이_entrant가_활성화만_된다() {
        User seller = persistUser("seller@vintic.local");
        User entrantUser = persistUser("entrant@vintic.local");
        Auction auction = persistLiveAuction(seller);
        AutoBidSetting entrant = AutoBidSetting.reserve(auction, entrantUser, 200000L);
        flushAndClear();

        Auction lockedAuction = auctionRepository.findByIdForUpdate(auction.getId()).orElseThrow();
        AutoBidEntrantOutcome outcome = proxyPriceEngine.resolveForAutoBidEntrant(lockedAuction, entrant);

        assertThat(outcome.bidOccurred()).isFalse();
        assertThat(outcome.resultingBidAmount()).isNull();
        assertThat(outcome.isHighestBidder()).isFalse();
        assertThat(lockedAuction.getCurrentPrice()).isEqualTo(105000L);
        assertThat(entrant.getStatus()).isEqualTo(AutoBidSettingStatus.ACTIVE);
    }

    @Test
    void entrant_자신이_이미_currentWinner면_자기자신과_경쟁하지_않는다() {
        User seller = persistUser("seller@vintic.local");
        User entrantUser = persistUser("entrant@vintic.local");
        Auction auction = persistLiveAuction(seller);
        AutoBidSetting entrant = persistActiveAutoBid(auction, entrantUser, 200000L);
        // entrant를 currentWinner로 만든다(직접 manual bid를 흉내내지 않고 도메인 메서드로 직접 반영).
        auction.applyProxyResult(entrantUser, 110000L);
        auctionRepository.saveAndFlush(auction);
        flushAndClear();

        Auction lockedAuction = auctionRepository.findByIdForUpdate(auction.getId()).orElseThrow();
        AutoBidSetting reloadedEntrant = autoBidSettingRepository.findById(entrant.getId()).orElseThrow();
        Long priceBefore = lockedAuction.getCurrentPrice();

        AutoBidEntrantOutcome outcome = proxyPriceEngine.resolveForAutoBidEntrant(lockedAuction, reloadedEntrant);

        assertThat(outcome.bidOccurred()).isFalse();
        assertThat(outcome.isHighestBidder()).isTrue();
        assertThat(lockedAuction.getCurrentPrice()).isEqualTo(priceBefore);
    }

    @Test
    void currentWinner가_AutoBid_없는_manual_bidder면_entrant는_한_단계만_응찰해서_이긴다() {
        User seller = persistUser("seller@vintic.local");
        User manualWinner = persistUser("manual@vintic.local");
        User entrantUser = persistUser("entrant@vintic.local");
        Auction auction = persistLiveAuction(seller);
        auction.applyProxyResult(manualWinner, 150000L); // manual-only winner, no AutoBid backing
        auctionRepository.saveAndFlush(auction);
        AutoBidSetting entrant = AutoBidSetting.reserve(auction, entrantUser, 300000L);
        flushAndClear();

        Auction lockedAuction = auctionRepository.findByIdForUpdate(auction.getId()).orElseThrow();

        AutoBidEntrantOutcome outcome = proxyPriceEngine.resolveForAutoBidEntrant(lockedAuction, entrant);

        assertThat(outcome.bidOccurred()).isTrue();
        assertThat(outcome.resultingBidAmount()).isEqualTo(155000L); // 150000 + increment(5000), not entrant's full cap
        assertThat(outcome.isHighestBidder()).isTrue();
        assertThat(lockedAuction.getCurrentPrice()).isEqualTo(155000L);
        assertThat(lockedAuction.getCurrentWinner().getId()).isEqualTo(entrantUser.getId());
    }

    @Test
    void 경쟁자_effectiveCap이_더_높으면_entrant는_지고_CAP_REACHED가_된다() {
        User seller = persistUser("seller@vintic.local");
        User competitorUser = persistUser("competitor@vintic.local");
        User entrantUser = persistUser("entrant@vintic.local");
        Auction auction = persistLiveAuction(seller);
        AutoBidSetting competitor = persistActiveAutoBid(auction, competitorUser, 500000L);
        auction.applyProxyResult(competitorUser, 105000L);
        auctionRepository.saveAndFlush(auction);
        flushAndClear();

        Auction lockedAuction = auctionRepository.findByIdForUpdate(auction.getId()).orElseThrow();
        AutoBidSetting reloadedEntrant = AutoBidSetting.reserve(lockedAuction, entrantUser, 200000L);

        AutoBidEntrantOutcome outcome = proxyPriceEngine.resolveForAutoBidEntrant(lockedAuction, reloadedEntrant);

        assertThat(outcome.bidOccurred()).isFalse();
        assertThat(outcome.isHighestBidder()).isFalse();
        assertThat(reloadedEntrant.getStatus()).isEqualTo(AutoBidSettingStatus.CAP_REACHED);
        // competitor는 entrant의 cap(200000)을 근소하게 넘는 선까지만 응찰한다.
        assertThat(lockedAuction.getCurrentPrice()).isEqualTo(205000L);
        assertThat(lockedAuction.getCurrentWinner().getId()).isEqualTo(competitorUser.getId());
        assertThat(bidRepository.findAll().stream().filter(b -> b.getBidType() == BidType.AUTO)).hasSize(1);
    }

    @Test
    void 경쟁자_effectiveCap이_더_낮으면_entrant가_이기고_경쟁자는_CAP_REACHED가_된다() {
        User seller = persistUser("seller@vintic.local");
        User competitorUser = persistUser("competitor@vintic.local");
        User entrantUser = persistUser("entrant@vintic.local");
        Auction auction = persistLiveAuction(seller);
        AutoBidSetting competitor = persistActiveAutoBid(auction, competitorUser, 120000L);
        auction.applyProxyResult(competitorUser, 105000L);
        auctionRepository.saveAndFlush(auction);
        flushAndClear();

        Auction lockedAuction = auctionRepository.findByIdForUpdate(auction.getId()).orElseThrow();
        AutoBidSetting reloadedEntrant = AutoBidSetting.reserve(lockedAuction, entrantUser, 300000L);

        AutoBidEntrantOutcome outcome = proxyPriceEngine.resolveForAutoBidEntrant(lockedAuction, reloadedEntrant);

        assertThat(outcome.bidOccurred()).isTrue();
        assertThat(outcome.isHighestBidder()).isTrue();
        assertThat(reloadedEntrant.getStatus()).isEqualTo(AutoBidSettingStatus.ACTIVE);
        assertThat(lockedAuction.getCurrentPrice()).isEqualTo(125000L); // min(300000, 120000+5000)
        AutoBidSetting reloadedCompetitor = autoBidSettingRepository.findById(competitor.getId()).orElseThrow();
        assertThat(reloadedCompetitor.getStatus()).isEqualTo(AutoBidSettingStatus.CAP_REACHED);
    }

    @Test
    void effectiveCap이_같으면_먼저_등록된_쪽이_이긴다() {
        User seller = persistUser("seller@vintic.local");
        User earlierUser = persistUser("earlier@vintic.local");
        User laterUser = persistUser("later@vintic.local");
        Auction auction = persistLiveAuction(seller);
        AutoBidSetting earlier = persistActiveAutoBid(auction, earlierUser, 200000L);
        auction.applyProxyResult(earlierUser, 105000L);
        auctionRepository.saveAndFlush(auction);
        flushAndClear();

        Auction lockedAuction = auctionRepository.findByIdForUpdate(auction.getId()).orElseThrow();
        // later의 effectiveCap도 200000으로 동일 - 하지만 나중에 등록됐으니 져야 한다(FIRST-IN WINS).
        AutoBidSetting laterEntrant = AutoBidSetting.reserve(lockedAuction, laterUser, 200000L);

        AutoBidEntrantOutcome outcome = proxyPriceEngine.resolveForAutoBidEntrant(lockedAuction, laterEntrant);

        assertThat(outcome.isHighestBidder()).isFalse();
        assertThat(laterEntrant.getStatus()).isEqualTo(AutoBidSettingStatus.CAP_REACHED);
        assertThat(lockedAuction.getCurrentWinner().getId()).isEqualTo(earlierUser.getId());
        assertThat(lockedAuction.getCurrentPrice()).isEqualTo(200000L); // 동률이라 추가로 올리지 않는다.
        AutoBidSetting reloadedEarlier = autoBidSettingRepository.findById(earlier.getId()).orElseThrow();
        assertThat(reloadedEarlier.getStatus()).isEqualTo(AutoBidSettingStatus.ACTIVE);
    }

    // #41(Proxy 미구현 기간)에 생성된 LIVE 데이터에는 여러 명이 동시에 ACTIVE로 남아있을 수 있다.
    // 이 테스트는 그런 dirty 상태에서도 objectively 가장 강한 경쟁자를 기준으로 정상 판정하는지
    // (잘못된 winner가 나오지 않는지) 확인한다 - 사용자 요청에 따른 필수 케이스.
    @Test
    void 복수_ACTIVE_dirty_data_상태에서도_가장_강한_경쟁자_기준으로_정상_판정된다() {
        User seller = persistUser("seller@vintic.local");
        User weakUser = persistUser("weak@vintic.local");
        User strongUser = persistUser("strong@vintic.local");
        User entrantUser = persistUser("entrant@vintic.local");
        Auction auction = persistLiveAuction(seller);
        // 정상이라면 하나만 ACTIVE여야 하지만, 여기서는 둘 다 ACTIVE로 남겨 dirty 상태를 흉내낸다.
        AutoBidSetting weak = persistActiveAutoBid(auction, weakUser, 130000L);
        AutoBidSetting strong = persistActiveAutoBid(auction, strongUser, 400000L);
        auction.applyProxyResult(weakUser, 105000L); // currentWinner 기록은 약한 쪽으로 잘못 남아있다고 가정
        auctionRepository.saveAndFlush(auction);
        flushAndClear();

        Auction lockedAuction = auctionRepository.findByIdForUpdate(auction.getId()).orElseThrow();
        AutoBidSetting entrant = AutoBidSetting.reserve(lockedAuction, entrantUser, 200000L);

        AutoBidEntrantOutcome outcome = proxyPriceEngine.resolveForAutoBidEntrant(lockedAuction, entrant);

        // entrant(200000)는 strong(400000)에는 못 미치지만 weak(130000)보다는 세다 - 기록된
        // currentWinner(weak)만 봤다면 entrant가 잘못 이겼을 것이다. 실제로는 strong을 찾아내
        // entrant가 져야 한다.
        assertThat(outcome.isHighestBidder()).isFalse();
        assertThat(entrant.getStatus()).isEqualTo(AutoBidSettingStatus.CAP_REACHED);
        assertThat(lockedAuction.getCurrentWinner().getId()).isEqualTo(strongUser.getId());
        assertThat(lockedAuction.getCurrentPrice()).isEqualTo(205000L); // min(400000, 200000+5000)

        AutoBidSetting reloadedStrong = autoBidSettingRepository.findById(strong.getId()).orElseThrow();
        assertThat(reloadedStrong.getStatus()).isEqualTo(AutoBidSettingStatus.ACTIVE);
        // weak는 이번 resolution에서 직접 다루지 않았으므로 여전히 ACTIVE로 남는다(점진적 정상화 -
        // 다음에 weak 자신이 트리거가 되거나 경쟁 대상으로 다시 조회될 때 정리된다).
        AutoBidSetting reloadedWeak = autoBidSettingRepository.findById(weak.getId()).orElseThrow();
        assertThat(reloadedWeak.getStatus()).isEqualTo(AutoBidSettingStatus.ACTIVE);
    }

    @Test
    void resolveAfterManualBid는_경쟁자가_없으면_반격하지_않는다() {
        User seller = persistUser("seller@vintic.local");
        User manualBidder = persistUser("manual@vintic.local");
        Auction auction = persistLiveAuction(seller);
        auction.applyProxyResult(manualBidder, 150000L);
        auctionRepository.saveAndFlush(auction);
        flushAndClear();

        Auction lockedAuction = auctionRepository.findByIdForUpdate(auction.getId()).orElseThrow();

        ManualBidCounterOutcome outcome = proxyPriceEngine.resolveAfterManualBid(lockedAuction, manualBidder.getId());

        assertThat(outcome.proxyResponded()).isFalse();
        assertThat(lockedAuction.getCurrentPrice()).isEqualTo(150000L);
    }

    @Test
    void resolveAfterManualBid는_경쟁_AutoBid의_cap이_충분하면_즉시_반격한다() {
        User seller = persistUser("seller@vintic.local");
        User manualBidder = persistUser("manual@vintic.local");
        User autoBidder = persistUser("auto@vintic.local");
        Auction auction = persistLiveAuction(seller);
        AutoBidSetting competitor = persistActiveAutoBid(auction, autoBidder, 300000L);
        auction.applyProxyResult(manualBidder, 150000L);
        auctionRepository.saveAndFlush(auction);
        flushAndClear();

        Auction lockedAuction = auctionRepository.findByIdForUpdate(auction.getId()).orElseThrow();

        ManualBidCounterOutcome outcome = proxyPriceEngine.resolveAfterManualBid(lockedAuction, manualBidder.getId());

        assertThat(outcome.proxyResponded()).isTrue();
        assertThat(lockedAuction.getCurrentPrice()).isEqualTo(155000L);
        assertThat(lockedAuction.getCurrentWinner().getId()).isEqualTo(autoBidder.getId());
    }

    @Test
    void resolveAfterManualBid는_경쟁_AutoBid의_cap이_이미_모자라면_반격하지_않고_CAP_REACHED로_정리한다() {
        User seller = persistUser("seller@vintic.local");
        User manualBidder = persistUser("manual@vintic.local");
        User autoBidder = persistUser("auto@vintic.local");
        Auction auction = persistLiveAuction(seller);
        // dirty data: cap(110000)이 이미 manual bid(150000)보다 낮은데도 ACTIVE로 남아있다고 가정.
        AutoBidSetting stale = persistActiveAutoBid(auction, autoBidder, 110000L);
        auction.applyProxyResult(manualBidder, 150000L);
        auctionRepository.saveAndFlush(auction);
        flushAndClear();

        Auction lockedAuction = auctionRepository.findByIdForUpdate(auction.getId()).orElseThrow();

        ManualBidCounterOutcome outcome = proxyPriceEngine.resolveAfterManualBid(lockedAuction, manualBidder.getId());

        assertThat(outcome.proxyResponded()).isFalse();
        assertThat(lockedAuction.getCurrentPrice()).isEqualTo(150000L);
        AutoBidSetting reloadedStale = autoBidSettingRepository.findById(stale.getId()).orElseThrow();
        assertThat(reloadedStale.getStatus()).isEqualTo(AutoBidSettingStatus.CAP_REACHED);
    }
}
