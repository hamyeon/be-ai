package com.vintic.backend.bid.service;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.bid.dto.PlaceBidResponse;
import com.vintic.backend.bid.repository.BidRepository;
import com.vintic.backend.common.exception.AlreadyHighestBidderException;
import com.vintic.backend.common.exception.AuctionClosedException;
import com.vintic.backend.common.exception.AuctionNotStartedException;
import com.vintic.backend.common.exception.BidAmountTooLowException;
import com.vintic.backend.common.exception.PenaltyRestrictedException;
import com.vintic.backend.common.exception.SellerCannotBidException;
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

// @DataJpaTest 슬라이스에 서비스를 직접 Import해, 실제 저장/갱신 결과를 DB 재조회로 검증한다.
@DataJpaTest
@Import(BidCommandService.class)
class BidCommandServiceTest {

    @Autowired
    private BidCommandService bidCommandService;

    @Autowired
    private AuctionRepository auctionRepository;

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
        assertThat(response.currentWinnerId()).isEqualTo(bidder.getId());

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
        ReflectionTestUtils.setField(bidder, "bidRestrictedUntil", LocalDateTime.now().plusDays(1));
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
        ReflectionTestUtils.setField(bidder, "bidRestrictedUntil", LocalDateTime.now().minusDays(1));
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        flushAndClear();

        PlaceBidResponse response = bidCommandService.placeManualBid(auction.getId(), bidder.getId(), 15000L);

        assertThat(response.currentWinnerId()).isEqualTo(bidder.getId());
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
}
