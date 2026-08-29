package com.vintic.backend.bid.service;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.autobid.domain.AutoBidSetting;
import com.vintic.backend.autobid.domain.AutoBidSettingStatus;
import com.vintic.backend.autobid.repository.AutoBidSettingRepository;
import com.vintic.backend.bid.domain.Bid;
import com.vintic.backend.bid.domain.BidType;
import com.vintic.backend.bid.dto.PlaceBidResponse;
import com.vintic.backend.bid.repository.BidRepository;
import com.vintic.backend.common.exception.AlreadyHighestBidderException;
import com.vintic.backend.common.exception.AuctionClosedException;
import com.vintic.backend.common.exception.AuctionNotStartedException;
import com.vintic.backend.common.exception.BidAmountTooLowException;
import com.vintic.backend.common.exception.PenaltyRestrictedException;
import com.vintic.backend.common.exception.SellerCannotBidException;
import com.vintic.backend.autobid.proxy.ProxyPriceEngine;
import com.vintic.backend.product.domain.Product;
import com.vintic.backend.support.TestClockConfig;
import com.vintic.backend.user.domain.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// @DataJpaTest 슬라이스에 서비스를 직접 Import해, 실제 저장/갱신 결과를 DB 재조회로 검증한다.
// ProxyPriceEngine/Clock(#41+): BidCommandService의 신규 의존성 - 이 슬라이스엔 자동으로 없어 명시 Import.
@DataJpaTest
@Import({BidCommandService.class, ProxyPriceEngine.class, TestClockConfig.class})
class BidCommandServiceTest {

    @Autowired
    private BidCommandService bidCommandService;

    @Autowired
    private AuctionRepository auctionRepository;

    @Autowired
    private BidRepository bidRepository;

    @Autowired
    private AutoBidSettingRepository autoBidSettingRepository;

    @Autowired
    private EntityManager entityManager;

    private User persistUser(String email) {
        User user = User.register(email, email, null);
        entityManager.persist(user);
        return user;
    }

    private Product persistProduct(User seller) {
        Product product = new Product(
                seller,
                List.of("https://example.com/a.jpg"),
                "Nike", "Dunk Low", "Panda", 270, "B", "PARTIAL",
                300000, 350000, "285,000원 ~ 315,000원", 290000, "사유", "설명"
        );
        entityManager.persist(product);
        return product;
    }

    private Auction persistLiveAuction(Product product) {
        Auction auction = Auction.schedule(
                product, 10000L, 5000L, LocalDateTime.now().plusHours(1), LocalDateTime.now().plusHours(2)
        );
        auction.start();
        entityManager.persist(auction);
        return auction;
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void LIVE_경매에_최소금액으로_입찰하면_성공하고_Auction_Bid가_갱신된다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        flushAndClear();

        PlaceBidResponse response = bidCommandService.placeManualBid(auction.getId(), bidder.getId(), 15000L);

        assertThat(response.submittedAmount()).isEqualTo(15000L);
        assertThat(response.currentPrice()).isEqualTo(15000L);
        assertThat(response.isHighestBidder()).isTrue();
        assertThat(response.highestBidderMasked()).isNotNull();

        Auction reloaded = auctionRepository.findById(auction.getId()).orElseThrow();
        assertThat(reloaded.getCurrentPrice()).isEqualTo(15000L);
        assertThat(reloaded.getCurrentWinner().getId()).isEqualTo(bidder.getId());
        assertThat(bidRepository.countByAuctionId(auction.getId())).isEqualTo(1);
    }

    @Test
    void 최소금액_미만이면_실패하고_Auction_Bid가_바뀌지_않는다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        flushAndClear();

        assertThatThrownBy(() -> bidCommandService.placeManualBid(auction.getId(), bidder.getId(), 14999L))
                .isInstanceOf(BidAmountTooLowException.class);

        Auction reloaded = auctionRepository.findById(auction.getId()).orElseThrow();
        assertThat(reloaded.getCurrentPrice()).isEqualTo(10000L);
        assertThat(reloaded.getCurrentWinner()).isNull();
        assertThat(bidRepository.countByAuctionId(auction.getId())).isZero();
    }

    @Test
    void SCHEDULED_경매에_입찰하면_실패하고_Auction_Bid가_바뀌지_않는다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = Auction.schedule(
                product, 10000L, 5000L, LocalDateTime.now().plusHours(1), LocalDateTime.now().plusHours(2)
        );
        entityManager.persist(auction);
        flushAndClear();

        assertThatThrownBy(() -> bidCommandService.placeManualBid(auction.getId(), bidder.getId(), 15000L))
                .isInstanceOf(AuctionNotStartedException.class);

        Auction reloaded = auctionRepository.findById(auction.getId()).orElseThrow();
        assertThat(reloaded.getCurrentPrice()).isEqualTo(10000L);
        assertThat(reloaded.getCurrentWinner()).isNull();
        assertThat(bidRepository.countByAuctionId(auction.getId())).isZero();
    }

    @Test
    void ENDED_경매에_입찰하면_실패하고_Auction_Bid가_바뀌지_않는다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        auction.end();
        flushAndClear();

        assertThatThrownBy(() -> bidCommandService.placeManualBid(auction.getId(), bidder.getId(), 15000L))
                .isInstanceOf(AuctionClosedException.class);

        Auction reloaded = auctionRepository.findById(auction.getId()).orElseThrow();
        assertThat(reloaded.getCurrentPrice()).isEqualTo(10000L);
        assertThat(reloaded.getCurrentWinner()).isNull();
        assertThat(bidRepository.countByAuctionId(auction.getId())).isZero();
    }

    @Test
    void CANCELED_경매에_입찰하면_실패하고_Auction_Bid가_바뀌지_않는다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = Auction.schedule(
                product, 10000L, 5000L, LocalDateTime.now().plusHours(1), LocalDateTime.now().plusHours(2)
        );
        auction.cancel();
        entityManager.persist(auction);
        flushAndClear();

        assertThatThrownBy(() -> bidCommandService.placeManualBid(auction.getId(), bidder.getId(), 15000L))
                .isInstanceOf(AuctionClosedException.class);

        Auction reloaded = auctionRepository.findById(auction.getId()).orElseThrow();
        assertThat(reloaded.getCurrentPrice()).isEqualTo(10000L);
        assertThat(reloaded.getCurrentWinner()).isNull();
        assertThat(bidRepository.countByAuctionId(auction.getId())).isZero();
    }

    @Test
    void 판매자_본인이_입찰하면_실패하고_Auction_Bid가_바뀌지_않는다() {
        User seller = persistUser("seller@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        flushAndClear();

        assertThatThrownBy(() -> bidCommandService.placeManualBid(auction.getId(), seller.getId(), 15000L))
                .isInstanceOf(SellerCannotBidException.class);

        Auction reloaded = auctionRepository.findById(auction.getId()).orElseThrow();
        assertThat(reloaded.getCurrentPrice()).isEqualTo(10000L);
        assertThat(reloaded.getCurrentWinner()).isNull();
        assertThat(bidRepository.countByAuctionId(auction.getId())).isZero();
    }

    @Test
    void bidRestrictedUntil이_미래인_사용자는_입찰에_실패하고_Auction_Bid가_바뀌지_않는다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        // 서비스는 이제 TestClockConfig의 고정 시각을 기준으로 판정하므로, 실제 시스템 시각 기준
        // 상대값(now().plusDays)이 아니라 확실히 미래/과거인 절대 시각을 쓴다.
        ReflectionTestUtils.setField(bidder, "bidRestrictedUntil", LocalDateTime.of(2099, 1, 1, 0, 0));
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        flushAndClear();

        assertThatThrownBy(() -> bidCommandService.placeManualBid(auction.getId(), bidder.getId(), 15000L))
                .isInstanceOf(PenaltyRestrictedException.class);

        Auction reloaded = auctionRepository.findById(auction.getId()).orElseThrow();
        assertThat(reloaded.getCurrentPrice()).isEqualTo(10000L);
        assertThat(reloaded.getCurrentWinner()).isNull();
        assertThat(bidRepository.countByAuctionId(auction.getId())).isZero();
    }

    @Test
    void bidRestrictedUntil이_과거이거나_없는_사용자는_정상적으로_입찰할_수_있다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        ReflectionTestUtils.setField(bidder, "bidRestrictedUntil", LocalDateTime.of(2000, 1, 1, 0, 0));
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        flushAndClear();

        PlaceBidResponse response = bidCommandService.placeManualBid(auction.getId(), bidder.getId(), 15000L);

        assertThat(response.isHighestBidder()).isTrue();
    }

    @Test
    void 현재_최고입찰자가_추가로_직접_입찰하면_실패하고_Auction_Bid가_바뀌지_않는다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        flushAndClear();
        bidCommandService.placeManualBid(auction.getId(), bidder.getId(), 15000L);
        flushAndClear();

        assertThatThrownBy(() -> bidCommandService.placeManualBid(auction.getId(), bidder.getId(), 20000L))
                .isInstanceOf(AlreadyHighestBidderException.class);

        Auction reloaded = auctionRepository.findById(auction.getId()).orElseThrow();
        assertThat(reloaded.getCurrentPrice()).isEqualTo(15000L);
        assertThat(reloaded.getCurrentWinner().getId()).isEqualTo(bidder.getId());
        assertThat(bidRepository.countByAuctionId(auction.getId())).isEqualTo(1);
    }

    @Test
    void 연속된_정상_입찰_후_currentPrice는_단조증가하고_최종_Bid와_Auction_상태가_일치한다() {
        User seller = persistUser("seller@vintic.local");
        User bidderA = persistUser("bidderA@vintic.local");
        User bidderB = persistUser("bidderB@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        flushAndClear();

        PlaceBidResponse first = bidCommandService.placeManualBid(auction.getId(), bidderA.getId(), 15000L);
        flushAndClear();
        PlaceBidResponse second = bidCommandService.placeManualBid(auction.getId(), bidderB.getId(), 20000L);
        flushAndClear();
        PlaceBidResponse third = bidCommandService.placeManualBid(auction.getId(), bidderA.getId(), 25000L);
        flushAndClear();

        assertThat(first.currentPrice()).isLessThan(second.currentPrice());
        assertThat(second.currentPrice()).isLessThan(third.currentPrice());

        Auction reloaded = auctionRepository.findById(auction.getId()).orElseThrow();
        assertThat(reloaded.getCurrentPrice()).isEqualTo(third.submittedAmount());
        assertThat(reloaded.getCurrentWinner().getId()).isEqualTo(bidderA.getId());
        assertThat(bidRepository.countByAuctionId(auction.getId())).isEqualTo(3);
    }

    @Test
    void 기존_ACTIVE_AutoBid이_있는_사용자가_직접입찰하면_autoBidCanceled가_true다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        AutoBidSetting setting = AutoBidSetting.reserve(auction, bidder, 100000L);
        setting.activate();
        autoBidSettingRepository.saveAndFlush(setting);
        flushAndClear();

        PlaceBidResponse response = bidCommandService.placeManualBid(auction.getId(), bidder.getId(), 15000L);

        assertThat(response.autoBidCanceled()).isTrue();
        AutoBidSetting reloaded = autoBidSettingRepository.findById(setting.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AutoBidSettingStatus.CANCELED);
    }

    @Test
    void AutoBid이_없는_사용자가_직접입찰하면_autoBidCanceled가_false다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        flushAndClear();

        PlaceBidResponse response = bidCommandService.placeManualBid(auction.getId(), bidder.getId(), 15000L);

        assertThat(response.autoBidCanceled()).isFalse();
    }

    @Test
    void 검증에_실패한_직접입찰은_기존_AutoBid을_취소하지_않는다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        AutoBidSetting setting = AutoBidSetting.reserve(auction, bidder, 100000L);
        setting.activate();
        autoBidSettingRepository.saveAndFlush(setting);
        flushAndClear();

        assertThatThrownBy(() -> bidCommandService.placeManualBid(auction.getId(), bidder.getId(), 14999L))
                .isInstanceOf(BidAmountTooLowException.class);

        AutoBidSetting reloaded = autoBidSettingRepository.findById(setting.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AutoBidSettingStatus.ACTIVE);
    }

    @Test
    void 다른_사용자의_경쟁_AutoBid이_직접입찰을_즉시_반격하면_proxyResponded가_true다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        User autoBidder = persistUser("autobidder@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        AutoBidSetting competitor = AutoBidSetting.reserve(auction, autoBidder, 100000L);
        competitor.activate();
        autoBidSettingRepository.saveAndFlush(competitor);
        flushAndClear();

        PlaceBidResponse response = bidCommandService.placeManualBid(auction.getId(), bidder.getId(), 15000L);

        assertThat(response.proxyResponded()).isTrue();
        assertThat(response.isHighestBidder()).isFalse();
        assertThat(response.currentPrice()).isEqualTo(20000L); // min(100000, 15000+5000)

        Auction reloaded = auctionRepository.findById(auction.getId()).orElseThrow();
        assertThat(reloaded.getCurrentWinner().getId()).isEqualTo(autoBidder.getId());
        assertThat(bidRepository.countByAuctionId(auction.getId())).isEqualTo(2);
    }

    @Test
    void 경쟁_AutoBid이_없으면_proxyResponded가_false다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        flushAndClear();

        PlaceBidResponse response = bidCommandService.placeManualBid(auction.getId(), bidder.getId(), 15000L);

        assertThat(response.proxyResponded()).isFalse();
        assertThat(response.isHighestBidder()).isTrue();
    }

    // 경쟁 AutoBid의 effectiveCap이 manual bid 금액(M)과 정확히 같은 동률 케이스: 가격(M)은 그대로지만
    // FIRST-IN WINS로 기존 AutoBid가 승자 자리를 되찾는다. priceChanged=false라는 이유로 이 반격의
    // AUTO Bid persistence를 건너뛰면 안 된다는 것을 DB 재조회까지 포함해 검증한다.
    @Test
    void 경쟁_AutoBid의_effectiveCap이_manual_bid와_정확히_동률이면_가격은_그대로_winner만_바뀌고_AUTO_Bid가_저장된다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        User autoBidder = persistUser("autobidder@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product); // currentPrice=10000, bidIncrement=5000 -> minNextBidAmount=15000
        // 등록 시점(currentPrice=10000) 기준 minCapAmount(15000)와 같은 maxAmount로 등록해,
        // manual bid(15000) 반영 이후 effectiveCap도 정확히 15000이 되도록 만든다.
        AutoBidSetting competitor = AutoBidSetting.reserve(auction, autoBidder, 15000L);
        competitor.activate();
        autoBidSettingRepository.saveAndFlush(competitor);
        flushAndClear();

        PlaceBidResponse response = bidCommandService.placeManualBid(auction.getId(), bidder.getId(), 15000L);

        assertThat(response.currentPrice()).isEqualTo(15000L); // M 그대로, M+증분(20000)으로 밀지 않는다
        assertThat(response.proxyResponded()).isTrue();
        assertThat(response.isHighestBidder()).isFalse(); // winner는 manual bidder가 아니라 autoBidder다

        Auction reloadedAuction = auctionRepository.findById(auction.getId()).orElseThrow();
        assertThat(reloadedAuction.getCurrentPrice()).isEqualTo(15000L);
        assertThat(reloadedAuction.getCurrentWinner().getId()).isEqualTo(autoBidder.getId());

        // manual Bid(15000, MANUAL) + 반격 AUTO Bid(15000, AUTO) 두 건이 모두 저장돼야 한다.
        assertThat(bidRepository.countByAuctionId(auction.getId())).isEqualTo(2);
        List<Bid> autoBids = bidRepository.findAll().stream()
                .filter(b -> b.getAuction().getId().equals(auction.getId()) && b.getBidType() == BidType.AUTO)
                .toList();
        assertThat(autoBids).hasSize(1);
        assertThat(autoBids.get(0).getAmount()).isEqualTo(15000L);
        assertThat(autoBids.get(0).getUser().getId()).isEqualTo(autoBidder.getId());
        // 최종 Auction.currentWinner와 저장된 AUTO Bid의 bidder가 일치해야 한다.
        assertThat(autoBids.get(0).getUser().getId()).isEqualTo(reloadedAuction.getCurrentWinner().getId());
    }
}
