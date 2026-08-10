package com.vintic.backend.auction.domain;

import com.vintic.backend.common.exception.InvalidAuctionStatusException;
import com.vintic.backend.product.domain.Product;
import com.vintic.backend.user.domain.User;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuctionTest {

    private final User seller = User.register("seller@vintic.local", "seller", null);
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
}
