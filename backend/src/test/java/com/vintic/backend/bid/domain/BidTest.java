package com.vintic.backend.bid.domain;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.product.domain.Product;
import com.vintic.backend.user.domain.User;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BidTest {

    private final User seller = User.register("seller@vintic.local", "seller", null);
    private final User bidder = User.register("bidder@vintic.local", "bidder", null);
    private final Product product = new Product(
            seller,
            List.of("https://example.com/a.jpg"),
            "Nike", "Dunk Low", "Panda", 270, "B", "PARTIAL",
            300000, 350000, "285,000원 ~ 315,000원", 290000, "사유", "설명"
    );
    private final Auction auction = Auction.schedule(
            product, 10000L, 5000L, LocalDateTime.now().plusHours(1), LocalDateTime.now().plusHours(2)
    );

    @Test
    void 정상_입찰을_생성할_수_있다() {
        Bid bid = Bid.place(auction, bidder, 15000L, BidType.MANUAL);

        assertThat(bid.getAmount()).isEqualTo(15000L);
        assertThat(bid.getBidType()).isEqualTo(BidType.MANUAL);
        assertThat(bid.getUser()).isEqualTo(bidder);
        assertThat(bid.getAuction()).isEqualTo(auction);
    }

    @Test
    void 금액이_0이하면_생성에_실패한다() {
        assertThatThrownBy(() -> Bid.place(auction, bidder, 0L, BidType.MANUAL))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 금액이_음수면_생성에_실패한다() {
        assertThatThrownBy(() -> Bid.place(auction, bidder, -1000L, BidType.MANUAL))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 자동입찰_타입으로도_생성할_수_있다() {
        Bid bid = Bid.place(auction, bidder, 15000L, BidType.AUTO);

        assertThat(bid.getBidType()).isEqualTo(BidType.AUTO);
    }
}
