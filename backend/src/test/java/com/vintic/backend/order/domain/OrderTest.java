package com.vintic.backend.order.domain;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.common.exception.InvalidOrderStatusException;
import com.vintic.backend.product.domain.Product;
import com.vintic.backend.user.domain.User;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderTest {

    private final User seller = User.register("seller@vintic.local", "seller", null);
    private final User buyer = User.register("buyer@vintic.local", "buyer", null);
    private final Product product = new Product(
            seller,
            List.of("https://example.com/a.jpg"),
            "Nike", "Dunk Low", "Panda", 270, "B", "PARTIAL",
            300000, 350000, "285,000원 ~ 315,000원", 290000, "사유", "설명"
    );
    private final Auction auction = Auction.schedule(
            product, 10000L, 5000L, LocalDateTime.now().minusHours(2), LocalDateTime.now().minusHours(1)
    );

    private Order pendingOrder() {
        return Order.createForWinner(auction, buyer, 30000L, 3000L, LocalDateTime.now().plusHours(24));
    }

    @Test
    void PAYMENT_PENDING_주문은_취소하면_CANCELED가_된다() {
        Order order = pendingOrder();

        order.cancel();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELED);
    }

    @Test
    void 이미_CANCELED된_주문을_다시_취소하면_예외가_발생한다() {
        Order order = pendingOrder();
        order.cancel();

        assertThatThrownBy(order::cancel).isInstanceOf(InvalidOrderStatusException.class);
    }
}
