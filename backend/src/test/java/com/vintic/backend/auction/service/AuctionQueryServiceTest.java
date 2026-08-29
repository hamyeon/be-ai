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
import com.vintic.backend.bid.domain.Bid;
import com.vintic.backend.bid.domain.BidType;
import com.vintic.backend.bid.repository.BidRepository;
import com.vintic.backend.common.exception.AuctionNotFoundException;
import com.vintic.backend.product.domain.Product;
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

// @DataJpaTest 슬라이스에 서비스를 직접 Import해, sellerId/bidCount가 실제 저장된
// Product.seller / Bid 개수와 일치하는지 실제 DB 조회로 검증한다.
@DataJpaTest
@Import(AuctionQueryService.class)
class AuctionQueryServiceTest {

    @Autowired
    private AuctionQueryService auctionQueryService;

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
    void sellerId는_Product_seller_id와_같다() {
        User seller = User.register("seller@vintic.local", "seller", null);
        entityManager.persist(seller);
        Product product = new Product(
                seller,
                List.of("https://example.com/a.jpg"),
                "Nike", "Dunk Low", "Panda", 270, "B", "PARTIAL",
                300000, 350000, "285,000원 ~ 315,000원", 290000, "사유", "설명"
        );
        entityManager.persist(product);
        Auction auction = Auction.schedule(
                product, 10000L, 5000L, LocalDateTime.now().plusHours(1), LocalDateTime.now().plusHours(2)
        );
        entityManager.persist(auction);
        entityManager.flush();
        entityManager.clear();

        AuctionDetailResponse response = auctionQueryService.getAuctionDetail(auction.getId());

        assertThat(response.sellerId()).isEqualTo(seller.getId());
    }

    @Test
    void bidCount는_실제_저장된_입찰_개수와_같다() {
        User seller = User.register("seller@vintic.local", "seller", null);
        entityManager.persist(seller);
        User bidder = User.register("bidder@vintic.local", "bidder", null);
        entityManager.persist(bidder);
        Product product = new Product(
                seller,
                List.of("https://example.com/a.jpg"),
                "Nike", "Dunk Low", "Panda", 270, "B", "PARTIAL",
                300000, 350000, "285,000원 ~ 315,000원", 290000, "사유", "설명"
        );
        entityManager.persist(product);
        Auction auction = Auction.schedule(
                product, 10000L, 5000L, LocalDateTime.now().plusHours(1), LocalDateTime.now().plusHours(2)
        );
        entityManager.persist(auction);
        entityManager.persist(Bid.place(auction, bidder, 15000L, BidType.MANUAL));
        entityManager.persist(Bid.place(auction, bidder, 20000L, BidType.MANUAL));
        entityManager.flush();
        entityManager.clear();

        AuctionDetailResponse response = auctionQueryService.getAuctionDetail(auction.getId());

        assertThat(response.bidCount()).isEqualTo(2L);
    }

    @Test
    void 입찰이_없으면_bidCount는_0이다() {
        User seller = User.register("seller@vintic.local", "seller", null);
        entityManager.persist(seller);
        Product product = new Product(
                seller,
                List.of("https://example.com/a.jpg"),
                "Nike", "Dunk Low", "Panda", 270, "B", "PARTIAL",
                300000, 350000, "285,000원 ~ 315,000원", 290000, "사유", "설명"
        );
        entityManager.persist(product);
        Auction auction = Auction.schedule(
                product, 10000L, 5000L, LocalDateTime.now().plusHours(1), LocalDateTime.now().plusHours(2)
        );
        entityManager.persist(auction);
        entityManager.flush();
        entityManager.clear();

        AuctionDetailResponse response = auctionQueryService.getAuctionDetail(auction.getId());

        assertThat(response.bidCount()).isZero();
    }

    @Test
    void 존재하지_않는_경매를_조회하면_예외가_발생한다() {
        assertThatThrownBy(() -> auctionQueryService.getAuctionDetail(999L))
                .isInstanceOf(AuctionNotFoundException.class);
    }

    @Test
    void live_조회시_currentPrice_minNextBidAmount_bidIncrement_minCapAmount를_반환한다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        flushAndClear();

        AuctionLiveResponse response = auctionQueryService.getLiveView(auction.getId(), bidder.getId());

        assertThat(response.auctionId()).isEqualTo(auction.getId());
        assertThat(response.currentPrice()).isEqualTo(10000L);
        assertThat(response.minNextBidAmount()).isEqualTo(15000L);
        assertThat(response.bidIncrement()).isEqualTo(5000L);
        assertThat(response.minCapAmount()).isEqualTo(15000L);
        // DB 왕복 시 nanosecond precision이 잘려나가므로(H2 microsecond 저장) nanos는 비교에서 제외한다.
        assertThat(response.endsAt()).isEqualToIgnoringNanos(auction.getEndAt());
        assertThat(response.serverTime()).isNotNull();
    }

    @Test
    void live_조회시_최고입찰자가_없으면_highestBidderMasked는_null이고_isMine은_false다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        flushAndClear();

        AuctionLiveResponse response = auctionQueryService.getLiveView(auction.getId(), bidder.getId());

        assertThat(response.highestBidderMasked()).isNull();
        assertThat(response.isMine()).isFalse();
    }

    @Test
    void live_조회시_최고입찰자가_있으면_마스킹된_닉네임을_반환한다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        User viewer = persistUser("viewer@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        auction.placeManualBid(bidder, 15000L);
        flushAndClear();

        AuctionLiveResponse response = auctionQueryService.getLiveView(auction.getId(), viewer.getId());

        assertThat(response.highestBidderMasked()).isEqualTo("bid****");
        assertThat(response.isMine()).isFalse();
    }

    @Test
    void live_조회시_내가_최고입찰자면_isMine은_true다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        auction.placeManualBid(bidder, 15000L);
        flushAndClear();

        AuctionLiveResponse response = auctionQueryService.getLiveView(auction.getId(), bidder.getId());

        assertThat(response.isMine()).isTrue();
    }

    @Test
    void live_조회시_정상_입찰자는_canBid가_true이고_cannotBidReason은_null이다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        flushAndClear();

        AuctionLiveResponse response = auctionQueryService.getLiveView(auction.getId(), bidder.getId());

        assertThat(response.canBid()).isTrue();
        assertThat(response.cannotBidReason()).isNull();
        assertThat(response.bidRestrictedUntil()).isNull();
    }

    @Test
    void live_조회시_SCHEDULED_경매는_AUCTION_NOT_STARTED를_반환한다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = Auction.schedule(
                product, 10000L, 5000L, LocalDateTime.now().plusHours(1), LocalDateTime.now().plusHours(2)
        );
        entityManager.persist(auction);
        flushAndClear();

        AuctionLiveResponse response = auctionQueryService.getLiveView(auction.getId(), bidder.getId());

        assertThat(response.canBid()).isFalse();
        assertThat(response.cannotBidReason()).isEqualTo(CannotBidReason.AUCTION_NOT_STARTED);
    }

    @Test
    void live_조회시_ENDED_경매는_AUCTION_CLOSED를_반환한다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        auction.end();
        flushAndClear();

        AuctionLiveResponse response = auctionQueryService.getLiveView(auction.getId(), bidder.getId());

        assertThat(response.canBid()).isFalse();
        assertThat(response.cannotBidReason()).isEqualTo(CannotBidReason.AUCTION_CLOSED);
    }

    @Test
    void live_조회시_판매자_본인은_SELLER_CANNOT_BID를_반환한다() {
        User seller = persistUser("seller@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        flushAndClear();

        AuctionLiveResponse response = auctionQueryService.getLiveView(auction.getId(), seller.getId());

        assertThat(response.canBid()).isFalse();
        assertThat(response.cannotBidReason()).isEqualTo(CannotBidReason.SELLER_CANNOT_BID);
    }

    @Test
    void live_조회시_제재중인_사용자는_PENALTY_RESTRICTED와_해제시각을_반환한다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        LocalDateTime restrictedUntil = LocalDateTime.now().plusDays(1);
        ReflectionTestUtils.setField(bidder, "bidRestrictedUntil", restrictedUntil);
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        flushAndClear();

        AuctionLiveResponse response = auctionQueryService.getLiveView(auction.getId(), bidder.getId());

        assertThat(response.canBid()).isFalse();
        assertThat(response.cannotBidReason()).isEqualTo(CannotBidReason.PENALTY_RESTRICTED);
        // DB 왕복 시 nanosecond precision이 잘려나가므로(H2 microsecond 저장) nanos는 비교에서 제외한다.
        assertThat(response.bidRestrictedUntil()).isEqualToIgnoringNanos(restrictedUntil);
    }

    @Test
    void live_조회시_현재_최고입찰자는_ALREADY_HIGHEST_BIDDER를_반환한다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        auction.placeManualBid(bidder, 15000L);
        flushAndClear();

        AuctionLiveResponse response = auctionQueryService.getLiveView(auction.getId(), bidder.getId());

        assertThat(response.canBid()).isFalse();
        assertThat(response.cannotBidReason()).isEqualTo(CannotBidReason.ALREADY_HIGHEST_BIDDER);
    }

    @Test
    void live_조회시_자동입찰_설정이_없으면_myAutoBidStatus와_myCap은_null이다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        flushAndClear();

        AuctionLiveResponse response = auctionQueryService.getLiveView(auction.getId(), bidder.getId());

        assertThat(response.myAutoBidStatus()).isNull();
        assertThat(response.myCap()).isNull();
    }

    @Test
    void live_조회시_ACTIVE_자동입찰_설정을_그대로_반환한다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        AutoBidSetting setting = AutoBidSetting.reserve(auction, bidder, 100000L);
        setting.activate();
        entityManager.persist(setting);
        flushAndClear();

        AuctionLiveResponse response = auctionQueryService.getLiveView(auction.getId(), bidder.getId());

        assertThat(response.myAutoBidStatus()).isEqualTo(AutoBidSettingStatus.ACTIVE);
        assertThat(response.myCap()).isEqualTo(100000L);
    }

    @Test
    void live_조회시_CAP_REACHED_자동입찰_설정을_그대로_반환한다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        AutoBidSetting setting = AutoBidSetting.reserve(auction, bidder, 100000L);
        setting.activate();
        setting.markCapReached();
        entityManager.persist(setting);
        flushAndClear();

        AuctionLiveResponse response = auctionQueryService.getLiveView(auction.getId(), bidder.getId());

        assertThat(response.myAutoBidStatus()).isEqualTo(AutoBidSettingStatus.CAP_REACHED);
        assertThat(response.myCap()).isEqualTo(100000L);
    }

    @Test
    void live_조회시_CANCELED_자동입찰_설정은_myAutoBidStatus와_myCap을_null로_취급한다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        AutoBidSetting setting = AutoBidSetting.reserve(auction, bidder, 100000L);
        setting.cancel();
        entityManager.persist(setting);
        flushAndClear();

        AuctionLiveResponse response = auctionQueryService.getLiveView(auction.getId(), bidder.getId());

        assertThat(response.myAutoBidStatus()).isNull();
        assertThat(response.myCap()).isNull();
    }

    @Test
    void 존재하지_않는_경매의_live_조회는_예외가_발생한다() {
        assertThatThrownBy(() -> auctionQueryService.getLiveView(999L, 1L))
                .isInstanceOf(AuctionNotFoundException.class);
    }

    @Test
    void recommendation_조회시_aiRecommendedCap은_항상_minCapAmount와_같다() {
        User seller = persistUser("seller@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        flushAndClear();

        AutoBidRecommendationResponse response = auctionQueryService.getAutoBidRecommendation(auction.getId());

        assertThat(response.auctionId()).isEqualTo(auction.getId());
        assertThat(response.currentPrice()).isEqualTo(10000L);
        assertThat(response.minCapAmount()).isEqualTo(15000L);
        assertThat(response.bidIncrement()).isEqualTo(5000L);
        assertThat(response.aiRecommendedCap()).isEqualTo(response.minCapAmount());
    }

    @Test
    void 존재하지_않는_경매의_recommendation_조회는_예외가_발생한다() {
        assertThatThrownBy(() -> auctionQueryService.getAutoBidRecommendation(999L))
                .isInstanceOf(AuctionNotFoundException.class);
    }
}
