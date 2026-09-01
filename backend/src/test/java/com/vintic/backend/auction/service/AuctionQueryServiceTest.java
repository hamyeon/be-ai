package com.vintic.backend.auction.service;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.domain.AuctionStatus;
import com.vintic.backend.auction.domain.CannotBidReason;
import com.vintic.backend.auction.dto.AuctionDetailResponse;
import com.vintic.backend.auction.dto.AuctionLiveResponse;
import com.vintic.backend.auction.dto.SimilarAuctionsResponse;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.autobid.domain.AutoBidSetting;
import com.vintic.backend.autobid.domain.AutoBidSettingStatus;
import com.vintic.backend.autobid.dto.AutoBidRecommendationResponse;
import com.vintic.backend.autobid.repository.AutoBidSettingRepository;
import com.vintic.backend.bid.domain.Bid;
import com.vintic.backend.bid.domain.BidType;
import com.vintic.backend.bid.repository.BidRepository;
import com.vintic.backend.common.exception.AuctionNotFoundException;
import com.vintic.backend.common.util.TimePolicy;
import com.vintic.backend.config.ClockConfig;
import com.vintic.backend.like.domain.AuctionLike;
import com.vintic.backend.like.repository.AuctionLikeRepository;
import com.vintic.backend.product.domain.Product;
import com.vintic.backend.support.TestClockConfig;
import com.vintic.backend.user.domain.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

// @DataJpaTest 슬라이스에 서비스를 직접 Import해, sellerId/bidCount가 실제 저장된
// Product.seller / Bid 개수와 일치하는지 실제 DB 조회로 검증한다.
// Clock(#41+): AuctionQueryService의 신규 의존성 - TestClockConfig로 채운다.
@DataJpaTest
@Import({AuctionQueryService.class, TestClockConfig.class})
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
    private AuctionLikeRepository auctionLikeRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

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

    // AuctionQueryService에 주입되는 Clock은 TestClockConfig.FIXED_INSTANT로 고정돼 있다 - endAt
    // 경계값 테스트는 이 고정 시각 기준 상대값으로 만들어야 결정적으로 검증할 수 있다.
    private LocalDateTime fixedNow() {
        return LocalDateTime.ofInstant(TestClockConfig.FIXED_INSTANT, ClockConfig.APP_ZONE);
    }

    private Auction persistLiveAuctionEndingAt(Product product, LocalDateTime endAt) {
        Auction auction = Auction.schedule(product, 10000L, 5000L, endAt.minusHours(1), endAt);
        auction.start();
        entityManager.persist(auction);
        return auction;
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    // ===== GET /auctions/{id} (#55 FINAL contract) =====

    @Test
    void 상세조회는_product_필드를_반환한다() {
        User seller = persistUser("seller@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        flushAndClear();

        AuctionDetailResponse response = auctionQueryService.getAuctionDetail(auction.getId(), null);

        assertThat(response.product().productId()).isEqualTo(product.getId());
        assertThat(response.product().brand()).isEqualTo("Nike");
        assertThat(response.product().name()).contains("Nike").contains("Dunk Low").contains("Panda");
        assertThat(response.product().subName()).isEqualTo("Dunk Low");
        assertThat(response.product().grade()).isEqualTo("B");
        assertThat(response.product().imageUrls()).containsExactly("https://example.com/a.jpg");
    }

    @Test
    void 상세조회는_seller_필드를_반환한다() {
        User seller = persistUser("seller@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        flushAndClear();

        AuctionDetailResponse response = auctionQueryService.getAuctionDetail(auction.getId(), null);

        assertThat(response.seller().sellerId()).isEqualTo(seller.getId());
        assertThat(response.seller().nickname()).isEqualTo(seller.getNickname());
        // #55 DEFERRED DATA SOURCE GAP: Order 도메인이 없어(#56에서 구현 예정) 실제 판매
        // 완료 건수를 집계할 source가 없다 - 이 0은 "실제로 0건"이라는 의미가 아니라 non-null
        // 계약(Int, O)을 어기지 않기 위한 shape-only placeholder다.
        assertThat(response.seller().completedSalesCount()).isZero();
    }

    @Test
    void 상세조회는_description_minNextBidAmount_minCapAmount_serverTime을_반환한다() {
        User seller = persistUser("seller@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        flushAndClear();

        AuctionDetailResponse response = auctionQueryService.getAuctionDetail(auction.getId(), null);

        assertThat(response.description()).isEqualTo("설명");
        assertThat(response.minNextBidAmount()).isEqualTo(15000L);
        assertThat(response.minCapAmount()).isEqualTo(15000L);
        assertThat(response.serverTime()).isNotNull();
    }

    @Test
    void 상세조회는_Product_pricing_결과를_AI_필드로_반환한다() {
        User seller = persistUser("seller@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        flushAndClear();

        AuctionDetailResponse response = auctionQueryService.getAuctionDetail(auction.getId(), null);

        // aiEstimatedPrice/aiPriceReason은 Product.recommendedPrice/reason(실제 pricing 결과)을
        // 그대로 재사용한다 - fake 값이 아니다.
        assertThat(response.aiEstimatedPrice()).isEqualTo(300000L);
        assertThat(response.aiPriceReason()).isEqualTo("사유");
        // aiRecommendedAutoBidCap: §4에서 이미 확정된 정책(buyer 전용 추천 소스 없음)을 재사용 -
        // minCapAmount와 항상 같다.
        assertThat(response.aiRecommendedAutoBidCap()).isEqualTo(response.minCapAmount());
    }

    @Test
    void bidCount는_실제_저장된_입찰_개수와_같다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        entityManager.persist(Bid.place(auction, bidder, 15000L, BidType.MANUAL));
        entityManager.persist(Bid.place(auction, bidder, 20000L, BidType.MANUAL));
        flushAndClear();

        AuctionDetailResponse response = auctionQueryService.getAuctionDetail(auction.getId(), null);

        assertThat(response.bidCount()).isEqualTo(2);
    }

    @Test
    void 입찰이_없으면_bidCount는_0이다() {
        User seller = persistUser("seller@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        flushAndClear();

        AuctionDetailResponse response = auctionQueryService.getAuctionDetail(auction.getId(), null);

        assertThat(response.bidCount()).isZero();
    }

    @Test
    void 비로그인_조회는_isLiked_false와_중립_myState를_반환한다() {
        User seller = persistUser("seller@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        flushAndClear();

        AuctionDetailResponse response = auctionQueryService.getAuctionDetail(auction.getId(), null);

        assertThat(response.isLiked()).isFalse();
        assertThat(response.myState().isSeller()).isFalse();
        assertThat(response.myState().isHighestBidder()).isFalse();
        assertThat(response.myState().canBid()).isFalse();
        assertThat(response.myState().cannotBidReason()).isNull();
        assertThat(response.myState().autoBidStatus()).isNull();
    }

    @Test
    void 로그인_사용자가_좋아요했으면_isLiked는_true이고_likeCount는_실제_개수와_같다() {
        User seller = persistUser("seller@vintic.local");
        User liker = persistUser("liker@vintic.local");
        User otherLiker = persistUser("other-liker@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        entityManager.persist(AuctionLike.create(auction, liker));
        entityManager.persist(AuctionLike.create(auction, otherLiker));
        flushAndClear();

        AuctionDetailResponse response = auctionQueryService.getAuctionDetail(auction.getId(), liker.getId());

        assertThat(response.isLiked()).isTrue();
        assertThat(response.likeCount()).isEqualTo(2);
    }

    @Test
    void 좋아요하지_않은_로그인_사용자는_isLiked가_false다() {
        User seller = persistUser("seller@vintic.local");
        User viewer = persistUser("viewer@vintic.local");
        User otherLiker = persistUser("other-liker@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        entityManager.persist(AuctionLike.create(auction, otherLiker));
        flushAndClear();

        AuctionDetailResponse response = auctionQueryService.getAuctionDetail(auction.getId(), viewer.getId());

        assertThat(response.isLiked()).isFalse();
        assertThat(response.likeCount()).isEqualTo(1);
    }

    @Test
    void 판매자_본인이_조회하면_myState_isSeller는_true다() {
        User seller = persistUser("seller@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        flushAndClear();

        AuctionDetailResponse response = auctionQueryService.getAuctionDetail(auction.getId(), seller.getId());

        assertThat(response.myState().isSeller()).isTrue();
        assertThat(response.myState().canBid()).isFalse();
        assertThat(response.myState().cannotBidReason()).isEqualTo(CannotBidReason.SELLER_CANNOT_BID);
    }

    @Test
    void 최고입찰자가_조회하면_myState_isHighestBidder는_true다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        auction.placeManualBid(bidder, 15000L);
        flushAndClear();

        AuctionDetailResponse response = auctionQueryService.getAuctionDetail(auction.getId(), bidder.getId());

        assertThat(response.myState().isHighestBidder()).isTrue();
        assertThat(response.myState().canBid()).isFalse();
        assertThat(response.myState().cannotBidReason()).isEqualTo(CannotBidReason.ALREADY_HIGHEST_BIDDER);
    }

    @Test
    void myState는_ACTIVE_자동입찰_설정을_반환한다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        AutoBidSetting setting = AutoBidSetting.reserve(auction, bidder, 100000L);
        setting.activate();
        entityManager.persist(setting);
        flushAndClear();

        AuctionDetailResponse response = auctionQueryService.getAuctionDetail(auction.getId(), bidder.getId());

        assertThat(response.myState().autoBidStatus()).isEqualTo(AutoBidSettingStatus.ACTIVE);
        assertThat(response.myState().autoBidCap()).isEqualTo(100000L);
    }

    @Test
    void ENDED_경매는_winner가_있으면_finalPrice를_currentPrice로_반환한다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        auction.placeManualBid(bidder, 15000L);
        auction.end();
        flushAndClear();

        AuctionDetailResponse response = auctionQueryService.getAuctionDetail(auction.getId(), null);

        assertThat(response.status().name()).isEqualTo("ENDED");
        assertThat(response.finalPrice()).isEqualTo(15000L);
    }

    @Test
    void ENDED_경매라도_winner가_없으면_finalPrice는_null이다() {
        User seller = persistUser("seller@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        auction.end();
        flushAndClear();

        AuctionDetailResponse response = auctionQueryService.getAuctionDetail(auction.getId(), null);

        assertThat(response.finalPrice()).isNull();
    }

    @Test
    void LIVE_경매는_finalPrice가_null이다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        auction.placeManualBid(bidder, 15000L);
        flushAndClear();

        AuctionDetailResponse response = auctionQueryService.getAuctionDetail(auction.getId(), null);

        assertThat(response.finalPrice()).isNull();
    }

    @Test
    void 존재하지_않는_경매를_조회하면_예외가_발생한다() {
        assertThatThrownBy(() -> auctionQueryService.getAuctionDetail(999L, null))
                .isInstanceOf(AuctionNotFoundException.class);
    }

    // ===== GET /auctions/{id}/similar (#55) =====

    @Test
    void Similar는_같은_브랜드의_다른_경매만_반환하고_자기_자신은_제외한다() {
        User seller = persistUser("seller@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);

        Product sameBrand = new Product(
                seller, List.of("https://example.com/b.jpg"), "Nike", "Air Max", "White", 270, "A", "FULL",
                200000, 250000, "190,000원 ~ 210,000원", 195000, "사유2", "설명2"
        );
        entityManager.persist(sameBrand);
        Auction sameBrandAuction = persistLiveAuction(sameBrand);

        Product otherBrand = new Product(
                seller, List.of("https://example.com/c.jpg"), "Adidas", "Superstar", "Black", 270, "A", "FULL",
                150000, 180000, "140,000원 ~ 160,000원", 145000, "사유3", "설명3"
        );
        entityManager.persist(otherBrand);
        persistLiveAuction(otherBrand);
        flushAndClear();

        SimilarAuctionsResponse response = auctionQueryService.getSimilarAuctions(auction.getId(), null);

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).auctionId()).isEqualTo(sameBrandAuction.getId());
        assertThat(response.items().get(0).brand()).isEqualTo("Nike");
    }

    @Test
    void Similar_응답은_likeCount와_isLiked를_반환한다() {
        User seller = persistUser("seller@vintic.local");
        User viewer = persistUser("viewer@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);

        Product sameBrand = new Product(
                seller, List.of("https://example.com/b.jpg"), "Nike", "Air Max", "White", 270, "A", "FULL",
                200000, 250000, "190,000원 ~ 210,000원", 195000, "사유2", "설명2"
        );
        entityManager.persist(sameBrand);
        Auction sameBrandAuction = persistLiveAuction(sameBrand);
        entityManager.persist(AuctionLike.create(sameBrandAuction, viewer));
        flushAndClear();

        SimilarAuctionsResponse response = auctionQueryService.getSimilarAuctions(auction.getId(), viewer.getId());

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).likeCount()).isEqualTo(1);
        assertThat(response.items().get(0).isLiked()).isTrue();
    }

    @Test
    void Similar_후보가_없으면_빈_목록을_반환한다() {
        User seller = persistUser("seller@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        flushAndClear();

        SimilarAuctionsResponse response = auctionQueryService.getSimilarAuctions(auction.getId(), null);

        assertThat(response.items()).isEmpty();
    }

    @Test
    void Similar는_같은_브랜드_후보가_5개_이상이어도_최대_4개만_반환한다() {
        User seller = persistUser("seller@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);

        for (int i = 0; i < 5; i++) {
            Product sameBrand = new Product(
                    seller, List.of("https://example.com/many" + i + ".jpg"), "Nike", "Air Max " + i, "White", 270, "A", "FULL",
                    200000, 250000, "190,000원 ~ 210,000원", 195000, "사유" + i, "설명" + i
            );
            entityManager.persist(sameBrand);
            persistLiveAuction(sameBrand);
        }
        flushAndClear();

        SimilarAuctionsResponse response = auctionQueryService.getSimilarAuctions(auction.getId(), null);

        assertThat(response.items()).hasSize(4);
    }

    @Test
    void Similar는_endAt이_같아도_id_오름차순으로_deterministic하게_정렬된다() {
        User seller = persistUser("seller@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);

        LocalDateTime sameEndAt = LocalDateTime.now().plusHours(5);
        Product productB = new Product(
                seller, List.of("https://example.com/tie-b.jpg"), "Nike", "Model B", "White", 270, "A", "FULL",
                200000, 250000, "190,000원 ~ 210,000원", 195000, "사유B", "설명B"
        );
        entityManager.persist(productB);
        Auction auctionB = Auction.schedule(productB, 10000L, 5000L, LocalDateTime.now().minusHours(1), sameEndAt);
        auctionB.start();
        entityManager.persist(auctionB);

        Product productA = new Product(
                seller, List.of("https://example.com/tie-a.jpg"), "Nike", "Model A", "White", 270, "A", "FULL",
                200000, 250000, "190,000원 ~ 210,000원", 195000, "사유A", "설명A"
        );
        entityManager.persist(productA);
        Auction auctionA = Auction.schedule(productA, 10000L, 5000L, LocalDateTime.now().minusHours(1), sameEndAt);
        auctionA.start();
        entityManager.persist(auctionA);
        flushAndClear();

        // auctionB가 먼저 persist돼 auto-increment id가 더 작다 - endAt이 완전히 같으므로
        // id 오름차순(auctionB -> auctionA)으로만 순서가 결정돼야 한다(deterministic tie-break).
        SimilarAuctionsResponse response = auctionQueryService.getSimilarAuctions(auction.getId(), null);

        assertThat(response.items()).hasSize(2);
        assertThat(response.items().get(0).auctionId()).isEqualTo(auctionB.getId());
        assertThat(response.items().get(1).auctionId()).isEqualTo(auctionA.getId());
    }

    @Test
    void 존재하지_않는_경매의_Similar_조회는_예외가_발생한다() {
        assertThatThrownBy(() -> auctionQueryService.getSimilarAuctions(999L, null))
                .isInstanceOf(AuctionNotFoundException.class);
    }

    @Test
    void Similar_후보가_여러개여도_likeCount_isLiked_계산으로_추가_SELECT가_늘어나지_않는다() {
        User seller = persistUser("seller@vintic.local");
        User viewer = persistUser("viewer@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);

        for (int i = 0; i < 3; i++) {
            Product sameBrand = new Product(
                    seller, List.of("https://example.com/similar" + i + ".jpg"), "Nike", "Air Max " + i, "White", 270, "A", "FULL",
                    200000, 250000, "190,000원 ~ 210,000원", 195000, "사유" + i, "설명" + i
            );
            entityManager.persist(sameBrand);
            Auction similarAuction = persistLiveAuction(sameBrand);
            entityManager.persist(AuctionLike.create(similarAuction, viewer));
        }
        flushAndClear();

        SessionFactory sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
        Statistics statistics = sessionFactory.getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();

        SimilarAuctionsResponse response = auctionQueryService.getSimilarAuctions(auction.getId(), viewer.getId());
        response.items().forEach(item -> {
            assertThat(item.likeCount()).isEqualTo(1);
            assertThat(item.isLiked()).isTrue();
        });

        long queryCount = statistics.getPrepareStatementCount();
        // 기준 auction 조회(1) + 후보 목록(1, product fetch join) + likeCount 배치(1) +
        // isLiked 배치(1) + imageUrls(@ElementCollection, @BatchSize) 배치 로딩(1) = 5.
        // 후보가 3개든 30개든(@BatchSize(20) 한도 내) 이 값은 늘어나지 않아야 한다 - 실제로 이
        // 테스트가 @BatchSize 추가 전 imageUrls의 item당 반복 SELECT(7개)를 잡아냈다.
        assertThat(queryCount)
                .as("likeCount/isLiked/imageUrls 계산이 후보 개수만큼 추가 SELECT를 내면 안 된다(N+1)")
                .isLessThanOrEqualTo(5);
    }

    // ===== 기존 /live, /auto-bid/recommendation 회귀(변경 없음) =====

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
        assertThat(response.endsAt()).isCloseTo(TimePolicy.toApiTime(auction.getEndAt()), within(1, java.time.temporal.ChronoUnit.SECONDS));
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
    void live_조회시_endAt_직전이면_아직_canBid가_true다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuctionEndingAt(product, fixedNow().plusSeconds(1));
        flushAndClear();

        AuctionLiveResponse response = auctionQueryService.getLiveView(auction.getId(), bidder.getId());

        assertThat(response.canBid()).isTrue();
        assertThat(response.cannotBidReason()).isNull();
    }

    // #73 종료 전 확인된 gap: BidCommandService/AutoBidCommandService는 이제 Auction.
    // hasReachedDeadline()로 "status=LIVE지만 endAt은 이미 지난" 입찰을 AUCTION_CLOSED(40903)로
    // 거절한다. status만 보던 determineCannotBidReason()이 이 조건을 함께 보지 않으면, scheduler
    // polling 지연 구간(endIfDue()가 아직 안 돈 상태)에서 /live가 실제로는 거절될 입찰을
    // canBid=true로 보여주는 read/write 불일치가 생긴다.
    @Test
    void live_조회시_endAt이_지났지만_status가_아직_LIVE면_AUCTION_CLOSED를_반환한다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuctionEndingAt(product, fixedNow().minusSeconds(1));
        flushAndClear();

        AuctionLiveResponse response = auctionQueryService.getLiveView(auction.getId(), bidder.getId());

        assertThat(auction.getStatus()).isEqualTo(AuctionStatus.LIVE);
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
        assertThat(response.bidRestrictedUntil()).isCloseTo(TimePolicy.toApiTime(restrictedUntil), within(1, java.time.temporal.ChronoUnit.SECONDS));
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
