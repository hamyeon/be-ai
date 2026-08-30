package com.vintic.backend.auction.domain;

import com.vintic.backend.common.exception.AlreadyHighestBidderException;
import com.vintic.backend.common.exception.AuctionClosedException;
import com.vintic.backend.common.exception.AuctionNotStartedException;
import com.vintic.backend.common.exception.BidAmountTooLowException;
import com.vintic.backend.common.exception.BidNotAlignedException;
import com.vintic.backend.common.exception.InvalidAuctionStatusException;
import com.vintic.backend.common.exception.SellerCannotBidException;
import com.vintic.backend.product.domain.Product;
import com.vintic.backend.user.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuctionTest {

    private final User seller = User.register("seller@vintic.local", "seller", null);
    private final User bidder = User.register("bidder@vintic.local", "bidder", null);
    private final Product product = new Product(
            seller,
            java.util.List.of("https://example.com/a.jpg"),
            "Nike", "Dunk Low", "Panda", 270, "B", "PARTIAL",
            300000, 350000, "285,000원 ~ 315,000원", 290000, "사유", "설명"
    );

    private Auction schedule() {
        return Auction.schedule(
                product,
                10000L,
                5000L,
                LocalDateTime.now().plusHours(1),
                LocalDateTime.now().plusHours(2)
        );
    }

    @Test
    void 경매를_생성하면_SCHEDULED_상태이고_currentPrice는_startPrice와_같다() {
        Auction auction = schedule();

        assertThat(auction.getStatus()).isEqualTo(AuctionStatus.SCHEDULED);
        assertThat(auction.getCurrentPrice()).isEqualTo(auction.getStartPrice());
    }

    @Test
    void 종료시각이_시작시각보다_이후가_아니면_생성에_실패한다() {
        LocalDateTime now = LocalDateTime.now();

        assertThatThrownBy(() -> Auction.schedule(product, 10000L, 5000L, now, now))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 시작가가_0이하면_생성에_실패한다() {
        LocalDateTime now = LocalDateTime.now();

        assertThatThrownBy(() -> Auction.schedule(product, 0L, 5000L, now, now.plusHours(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void SCHEDULED에서_LIVE로_전이할_수_있다() {
        Auction auction = schedule();

        auction.start();

        assertThat(auction.getStatus()).isEqualTo(AuctionStatus.LIVE);
    }

    @Test
    void SCHEDULED에서_CANCELED로_전이할_수_있다() {
        Auction auction = schedule();

        auction.cancel();

        assertThat(auction.getStatus()).isEqualTo(AuctionStatus.CANCELED);
    }

    @Test
    void LIVE에서_ENDED로_전이할_수_있다() {
        Auction auction = schedule();
        auction.start();

        auction.end();

        assertThat(auction.getStatus()).isEqualTo(AuctionStatus.ENDED);
        assertThat(auction.getCurrentWinner()).isNull();
    }

    @Test
    void LIVE에서_CANCELED로는_전이할_수_없다() {
        Auction auction = schedule();
        auction.start();

        assertThatThrownBy(auction::cancel)
                .isInstanceOf(InvalidAuctionStatusException.class);
    }

    @Test
    void SCHEDULED가_아니면_start를_호출할_수_없다() {
        Auction auction = schedule();
        auction.cancel();

        assertThatThrownBy(auction::start)
                .isInstanceOf(InvalidAuctionStatusException.class);
    }

    @Test
    void LIVE가_아니면_end를_호출할_수_없다() {
        Auction auction = schedule();

        assertThatThrownBy(auction::end)
                .isInstanceOf(InvalidAuctionStatusException.class);
    }

    @Test
    void ENDED는_terminal_상태라_추가_전이가_불가능하다() {
        Auction auction = schedule();
        auction.start();
        auction.end();

        assertThatThrownBy(auction::end).isInstanceOf(InvalidAuctionStatusException.class);
        assertThatThrownBy(auction::cancel).isInstanceOf(InvalidAuctionStatusException.class);
        assertThatThrownBy(auction::start).isInstanceOf(InvalidAuctionStatusException.class);
    }

    @Test
    void LIVE_상태에서_정확히_최소금액으로_입찰하면_성공한다() {
        Auction auction = schedule();
        auction.start();

        auction.placeManualBid(bidder, 15000L);

        assertThat(auction.getCurrentPrice()).isEqualTo(15000L);
        assertThat(auction.getCurrentWinner()).isEqualTo(bidder);
    }

    @Test
    void 최소금액_미만으로_입찰하면_실패하고_currentPrice_currentWinner는_바뀌지_않는다() {
        Auction auction = schedule();
        auction.start();

        assertThatThrownBy(() -> auction.placeManualBid(bidder, 14999L))
                .isInstanceOf(BidAmountTooLowException.class);
        assertThat(auction.getCurrentPrice()).isEqualTo(10000L);
        assertThat(auction.getCurrentWinner()).isNull();
    }

    @Test
    void SCHEDULED_상태에서_입찰하면_실패하고_currentPrice_currentWinner는_바뀌지_않는다() {
        Auction auction = schedule();

        assertThatThrownBy(() -> auction.placeManualBid(bidder, 15000L))
                .isInstanceOf(AuctionNotStartedException.class);
        assertThat(auction.getCurrentPrice()).isEqualTo(10000L);
        assertThat(auction.getCurrentWinner()).isNull();
    }

    @Test
    void ENDED_상태에서_입찰하면_실패하고_currentPrice_currentWinner는_바뀌지_않는다() {
        Auction auction = schedule();
        auction.start();
        auction.end();

        assertThatThrownBy(() -> auction.placeManualBid(bidder, 15000L))
                .isInstanceOf(AuctionClosedException.class);
        assertThat(auction.getCurrentPrice()).isEqualTo(10000L);
        assertThat(auction.getCurrentWinner()).isNull();
    }

    @Test
    void CANCELED_상태에서_입찰하면_실패하고_currentPrice_currentWinner는_바뀌지_않는다() {
        Auction auction = schedule();
        auction.cancel();

        assertThatThrownBy(() -> auction.placeManualBid(bidder, 15000L))
                .isInstanceOf(AuctionClosedException.class);
        assertThat(auction.getCurrentPrice()).isEqualTo(10000L);
        assertThat(auction.getCurrentWinner()).isNull();
    }

    @Test
    void 판매자_본인이_입찰하면_실패하고_currentPrice_currentWinner는_바뀌지_않는다() {
        Auction auction = schedule();
        auction.start();

        assertThatThrownBy(() -> auction.placeManualBid(seller, 15000L))
                .isInstanceOf(SellerCannotBidException.class);
        assertThat(auction.getCurrentPrice()).isEqualTo(10000L);
        assertThat(auction.getCurrentWinner()).isNull();
    }

    @Test
    void 현재_최고입찰자가_추가로_직접_입찰하면_실패하고_currentPrice_currentWinner는_바뀌지_않는다() {
        Auction auction = schedule();
        auction.start();
        auction.placeManualBid(bidder, 15000L);

        assertThatThrownBy(() -> auction.placeManualBid(bidder, 20000L))
                .isInstanceOf(AlreadyHighestBidderException.class);
        assertThat(auction.getCurrentPrice()).isEqualTo(15000L);
        assertThat(auction.getCurrentWinner()).isEqualTo(bidder);
    }

    @Test
    void 현재가로부터_bidIncrement의_배수이면_최소금액_이상_입찰에_성공한다() {
        Auction auction = schedule();
        auction.start();

        auction.placeManualBid(bidder, 20000L); // currentPrice(10000) + 2*bidIncrement(5000)

        assertThat(auction.getCurrentPrice()).isEqualTo(20000L);
    }

    @Test
    void 최소금액_이상이지만_bidIncrement_배수가_아니면_BidNotAlignedException이_발생하고_상태가_바뀌지_않는다() {
        Auction auction = schedule();
        auction.start();

        assertThatThrownBy(() -> auction.placeManualBid(bidder, 17000L)) // 15000 이상이지만 5000의 배수가 아님
                .isInstanceOf(BidNotAlignedException.class);
        assertThat(auction.getCurrentPrice()).isEqualTo(10000L);
        assertThat(auction.getCurrentWinner()).isNull();
    }

    @Test
    void 최소금액_미만이면_배수가_맞아도_BidAmountTooLowException이_우선한다() {
        Auction auction = schedule();
        auction.start();

        // 5000의 배수이지만(10000+5000=15000 미만) 최소금액(15000) 미만인 값 -> 40904가 우선
        assertThatThrownBy(() -> auction.placeManualBid(bidder, 10000L))
                .isInstanceOf(BidAmountTooLowException.class);
    }

    @Test
    void getMinNextBidAmount는_currentPrice와_bidIncrement의_합이다() {
        Auction auction = schedule();

        assertThat(auction.getMinNextBidAmount()).isEqualTo(15000L);
    }

    @Test
    void getMinNextBidAmount는_입찰_후_갱신된_currentPrice를_반영한다() {
        Auction auction = schedule();
        auction.start();
        auction.placeManualBid(bidder, 15000L);

        assertThat(auction.getMinNextBidAmount()).isEqualTo(20000L);
    }

    @Test
    void determineCannotBidReason은_제재중인_사용자에게_PENALTY_RESTRICTED를_최우선으로_반환한다() {
        User restrictedBidder = User.register("restricted@vintic.local", "restricted", null);
        LocalDateTime now = LocalDateTime.now();
        restrict(restrictedBidder, now.plusDays(1));
        Auction auction = schedule();
        auction.start();

        assertThat(auction.determineCannotBidReason(restrictedBidder, now))
                .isEqualTo(CannotBidReason.PENALTY_RESTRICTED);
    }

    @Test
    void determineCannotBidReason은_제재중이면_판매자_본인이어도_PENALTY_RESTRICTED가_우선한다() {
        LocalDateTime now = LocalDateTime.now();
        restrict(seller, now.plusDays(1));
        Auction auction = schedule();
        auction.start();

        assertThat(auction.determineCannotBidReason(seller, now))
                .isEqualTo(CannotBidReason.PENALTY_RESTRICTED);
    }

    @Test
    void determineCannotBidReason은_SCHEDULED_상태면_AUCTION_NOT_STARTED를_반환한다() {
        Auction auction = schedule();

        assertThat(auction.determineCannotBidReason(bidder, LocalDateTime.now()))
                .isEqualTo(CannotBidReason.AUCTION_NOT_STARTED);
    }

    @Test
    void determineCannotBidReason은_ENDED_상태면_AUCTION_CLOSED를_반환한다() {
        Auction auction = schedule();
        auction.start();
        auction.end();

        assertThat(auction.determineCannotBidReason(bidder, LocalDateTime.now()))
                .isEqualTo(CannotBidReason.AUCTION_CLOSED);
    }

    @Test
    void determineCannotBidReason은_판매자_본인에게_SELLER_CANNOT_BID를_반환한다() {
        Auction auction = schedule();
        auction.start();

        assertThat(auction.determineCannotBidReason(seller, LocalDateTime.now()))
                .isEqualTo(CannotBidReason.SELLER_CANNOT_BID);
    }

    @Test
    void determineCannotBidReason은_현재_최고입찰자에게_ALREADY_HIGHEST_BIDDER를_반환한다() {
        Auction auction = schedule();
        auction.start();
        auction.placeManualBid(bidder, 15000L);

        assertThat(auction.determineCannotBidReason(bidder, LocalDateTime.now()))
                .isEqualTo(CannotBidReason.ALREADY_HIGHEST_BIDDER);
    }

    @Test
    void determineCannotBidReason은_입찰_가능한_사용자에게_null을_반환한다() {
        Auction auction = schedule();
        auction.start();

        assertThat(auction.determineCannotBidReason(bidder, LocalDateTime.now())).isNull();
    }

    private void restrict(User user, LocalDateTime until) {
        ReflectionTestUtils.setField(user, "bidRestrictedUntil", until);
    }

    // ===== 종료 연장(maybeExtend) =====

    private Auction liveAuctionEndingAt(LocalDateTime endAt) {
        Auction auction = Auction.schedule(product, 10000L, 5000L, endAt.minusHours(1), endAt);
        auction.start();
        return auction;
    }

    @Test
    void 종료_정확히_1분_전이면_연장된다() {
        LocalDateTime endAt = LocalDateTime.of(2026, 1, 1, 12, 0, 0);
        Auction auction = liveAuctionEndingAt(endAt);
        LocalDateTime now = endAt.minusMinutes(1); // 경계값: 정확히 1분 전(inclusive)

        boolean extended = auction.maybeExtend(now);

        assertThat(extended).isTrue();
        assertThat(auction.getEndAt()).isEqualTo(endAt.plusMinutes(3));
        assertThat(auction.getExtensionCount()).isEqualTo(1);
    }

    @Test
    void 종료_1분_초과_전이면_연장되지_않는다() {
        LocalDateTime endAt = LocalDateTime.of(2026, 1, 1, 12, 0, 0);
        Auction auction = liveAuctionEndingAt(endAt);
        LocalDateTime now = endAt.minusMinutes(1).minusSeconds(1); // 1분보다 1초 더 이전

        boolean extended = auction.maybeExtend(now);

        assertThat(extended).isFalse();
        assertThat(auction.getEndAt()).isEqualTo(endAt);
        assertThat(auction.getExtensionCount()).isZero();
    }

    @Test
    void 최대_3회까지만_연장되고_4번째_시도는_무시된다() {
        LocalDateTime endAt = LocalDateTime.of(2026, 1, 1, 12, 0, 0);
        Auction auction = liveAuctionEndingAt(endAt);

        // 매번 endAt 1분 전에 재도전한다고 가정 - 매 호출 시점의 (갱신된) endAt 기준 1분 전을 사용한다.
        for (int i = 0; i < Auction.MAX_EXTENSIONS; i++) {
            LocalDateTime now = auction.getEndAt().minusMinutes(1);
            assertThat(auction.maybeExtend(now)).isTrue();
        }
        assertThat(auction.getExtensionCount()).isEqualTo(Auction.MAX_EXTENSIONS);
        LocalDateTime endAtAfterMax = auction.getEndAt();

        boolean fourthAttempt = auction.maybeExtend(endAtAfterMax.minusMinutes(1));

        assertThat(fourthAttempt).isFalse();
        assertThat(auction.getExtensionCount()).isEqualTo(Auction.MAX_EXTENSIONS);
        assertThat(auction.getEndAt()).isEqualTo(endAtAfterMax);
    }
}
