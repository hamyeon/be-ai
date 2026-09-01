package com.vintic.backend.auction.service;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.domain.AuctionStatus;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.autobid.domain.AutoBidSetting;
import com.vintic.backend.autobid.domain.AutoBidSettingStatus;
import com.vintic.backend.autobid.proxy.ProxyPriceEngine;
import com.vintic.backend.autobid.repository.AutoBidSettingRepository;
import com.vintic.backend.bid.domain.Bid;
import com.vintic.backend.bid.domain.BidType;
import com.vintic.backend.bid.repository.BidRepository;
import com.vintic.backend.config.ClockConfig;
import com.vintic.backend.product.domain.Product;
import com.vintic.backend.support.TestClockConfig;
import com.vintic.backend.user.domain.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// #73-1: SCHEDULED -> LIVE + 시작 시 RESERVED AutoBid 일괄 정산(ProxyTrigger.None, #42가 미리
// 만들어둔 계산 shape의 첫 실제 호출부).
@DataJpaTest
@Import({AuctionStartService.class, ProxyPriceEngine.class, TestClockConfig.class})
class AuctionStartServiceTest {

    private static final LocalDateTime FIXED_NOW = LocalDateTime.ofInstant(TestClockConfig.FIXED_INSTANT, ClockConfig.APP_ZONE);

    @Autowired
    private AuctionStartService auctionStartService;

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

    private Auction persistAuction(Product product, LocalDateTime startAt) {
        Auction auction = Auction.schedule(product, 10000L, 5000L, startAt, startAt.plusHours(1));
        entityManager.persist(auction);
        return auction;
    }

    private AutoBidSetting persistReserved(Auction auction, User user, long maxAmount) {
        AutoBidSetting setting = AutoBidSetting.reserve(auction, user, maxAmount);
        entityManager.persist(setting);
        return setting;
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void startAt_이전이면_SCHEDULED로_유지된다() {
        User seller = persistUser("seller@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistAuction(product, FIXED_NOW.plusMinutes(1));
        flushAndClear();

        auctionStartService.startIfDue(auction.getId());

        Auction reloaded = auctionRepository.findById(auction.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AuctionStatus.SCHEDULED);
    }

    @Test
    void startAt_도달하면_LIVE로_전환된다() {
        User seller = persistUser("seller@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistAuction(product, FIXED_NOW.minusMinutes(1));
        flushAndClear();

        auctionStartService.startIfDue(auction.getId());

        Auction reloaded = auctionRepository.findById(auction.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AuctionStatus.LIVE);
    }

    @Test
    void 이미_LIVE인_경매는_재처리하지_않는다() {
        User seller = persistUser("seller@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistAuction(product, FIXED_NOW.minusMinutes(1));
        auction.start();
        flushAndClear();

        auctionStartService.startIfDue(auction.getId());

        Auction reloaded = auctionRepository.findById(auction.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AuctionStatus.LIVE);
    }

    @Test
    void 이미_ENDED인_경매는_재처리하지_않는다() {
        User seller = persistUser("seller@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistAuction(product, FIXED_NOW.minusMinutes(1));
        auction.start();
        auction.end();
        flushAndClear();

        auctionStartService.startIfDue(auction.getId());

        Auction reloaded = auctionRepository.findById(auction.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AuctionStatus.ENDED);
    }

    @Test
    void 이미_CANCELED인_경매는_재처리하지_않는다() {
        User seller = persistUser("seller@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistAuction(product, FIXED_NOW.plusMinutes(1));
        auction.cancel();
        flushAndClear();

        auctionStartService.startIfDue(auction.getId());

        Auction reloaded = auctionRepository.findById(auction.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AuctionStatus.CANCELED);
    }

    @Test
    void 존재하지_않는_경매를_처리해도_예외없이_아무일도_일어나지_않는다() {
        auctionStartService.startIfDue(9999L);
    }

    @Test
    void RESERVED_설정_1건은_ACTIVE로_전환되고_Bid가_생성된다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistAuction(product, FIXED_NOW.minusMinutes(1));
        persistReserved(auction, bidder, 30000L);
        flushAndClear();

        auctionStartService.startIfDue(auction.getId());

        Auction reloadedAuction = auctionRepository.findById(auction.getId()).orElseThrow();
        assertThat(reloadedAuction.getStatus()).isEqualTo(AuctionStatus.LIVE);
        // 예약자 1명도 최소 한 단계는 응찰해야 한다(§0.13) - startPrice(10000) + bidIncrement(5000).
        assertThat(reloadedAuction.getCurrentPrice()).isEqualTo(15000L);
        assertThat(reloadedAuction.getCurrentWinner().getId()).isEqualTo(bidder.getId());

        AutoBidSetting reloadedSetting = autoBidSettingRepository
                .findByAuctionIdAndUserIdAndActiveSlotTrue(auction.getId(), bidder.getId()).orElseThrow();
        assertThat(reloadedSetting.getStatus()).isEqualTo(AutoBidSettingStatus.ACTIVE);

        List<Bid> bids = bidRepository.findAll();
        assertThat(bids).hasSize(1);
        assertThat(bids.get(0).getBidType()).isEqualTo(BidType.AUTO);
        assertThat(bids.get(0).getAmount()).isEqualTo(15000L);
    }

    @Test
    void 상대보다_cap이_낮은_RESERVED는_CAP_REACHED가_된다() {
        User seller = persistUser("seller@vintic.local");
        User strong = persistUser("strong@vintic.local");
        User weak = persistUser("weak@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistAuction(product, FIXED_NOW.minusMinutes(1));
        persistReserved(auction, strong, 50000L);
        persistReserved(auction, weak, 15000L);
        flushAndClear();

        auctionStartService.startIfDue(auction.getId());

        Auction reloadedAuction = auctionRepository.findById(auction.getId()).orElseThrow();
        // top=strong(50000), second=weak(15000) -> finalPrice = min(50000, 15000+5000) = 20000.
        assertThat(reloadedAuction.getCurrentPrice()).isEqualTo(20000L);
        assertThat(reloadedAuction.getCurrentWinner().getId()).isEqualTo(strong.getId());

        AutoBidSetting strongSetting = autoBidSettingRepository
                .findByAuctionIdAndUserIdAndActiveSlotTrue(auction.getId(), strong.getId()).orElseThrow();
        AutoBidSetting weakSetting = autoBidSettingRepository
                .findByAuctionIdAndUserIdAndActiveSlotTrue(auction.getId(), weak.getId()).orElseThrow();
        assertThat(strongSetting.getStatus()).isEqualTo(AutoBidSettingStatus.ACTIVE);
        assertThat(weakSetting.getStatus()).isEqualTo(AutoBidSettingStatus.CAP_REACHED);
    }

    @Test
    void 동일_cap이면_FIRST_IN_WINS다() {
        User seller = persistUser("seller@vintic.local");
        User first = persistUser("first@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistAuction(product, FIXED_NOW.minusMinutes(1));
        AutoBidSetting firstSetting = persistReserved(auction, first, 30000L);
        flushAndClear();
        User second = persistUser("second@vintic.local");
        AutoBidSetting secondSetting = persistReserved(auction, second, 30000L);
        flushAndClear();

        auctionStartService.startIfDue(auction.getId());

        AutoBidSetting reloadedFirst = autoBidSettingRepository.findById(firstSetting.getId()).orElseThrow();
        AutoBidSetting reloadedSecond = autoBidSettingRepository.findById(secondSetting.getId()).orElseThrow();
        assertThat(reloadedFirst.getStatus()).isEqualTo(AutoBidSettingStatus.ACTIVE);
        assertThat(reloadedSecond.getStatus()).isEqualTo(AutoBidSettingStatus.CAP_REACHED);

        Auction reloadedAuction = auctionRepository.findById(auction.getId()).orElseThrow();
        assertThat(reloadedAuction.getCurrentWinner().getId()).isEqualTo(first.getId());
    }

    @Test
    void 반복_호출해도_중복_상태전이가_없다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistAuction(product, FIXED_NOW.minusMinutes(1));
        persistReserved(auction, bidder, 30000L);
        flushAndClear();

        auctionStartService.startIfDue(auction.getId());
        auctionStartService.startIfDue(auction.getId());
        auctionStartService.startIfDue(auction.getId());

        Auction reloadedAuction = auctionRepository.findById(auction.getId()).orElseThrow();
        assertThat(reloadedAuction.getStatus()).isEqualTo(AuctionStatus.LIVE);
        assertThat(reloadedAuction.getCurrentPrice()).isEqualTo(15000L);
        assertThat(bidRepository.findAll()).hasSize(1);

        AutoBidSetting reloadedSetting = autoBidSettingRepository
                .findByAuctionIdAndUserIdAndActiveSlotTrue(auction.getId(), bidder.getId()).orElseThrow();
        assertThat(reloadedSetting.getStatus()).isEqualTo(AutoBidSettingStatus.ACTIVE);
    }
}
