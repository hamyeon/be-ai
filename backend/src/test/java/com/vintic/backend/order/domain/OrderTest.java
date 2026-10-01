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

    @Test
    void PAYMENT_PENDING_주문은_결제하면_PAID가_되고_paidAt이_기록된다() {
        Order order = pendingOrder();
        LocalDateTime paidAt = LocalDateTime.now();

        order.pay(paidAt);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(order.getPaidAt()).isEqualTo(paidAt);
    }

    @Test
    void 이미_PAID된_주문을_다시_결제하면_예외가_발생한다() {
        Order order = pendingOrder();
        order.pay(LocalDateTime.now());

        assertThatThrownBy(() -> order.pay(LocalDateTime.now())).isInstanceOf(InvalidOrderStatusException.class);
    }

    @Test
    void CANCELED된_주문을_결제하면_예외가_발생한다() {
        Order order = pendingOrder();
        order.cancel();

        assertThatThrownBy(() -> order.pay(LocalDateTime.now())).isInstanceOf(InvalidOrderStatusException.class);
    }

    @Test
    void PAYMENT_PENDING_주문은_만료하면_PAYMENT_EXPIRED가_된다() {
        Order order = pendingOrder();

        order.expire();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_EXPIRED);
    }

    @Test
    void 이미_PAYMENT_EXPIRED된_주문을_다시_만료하면_예외가_발생한다() {
        Order order = pendingOrder();
        order.expire();

        assertThatThrownBy(order::expire).isInstanceOf(InvalidOrderStatusException.class);
    }

    @Test
    void PAID된_주문을_만료하면_예외가_발생한다() {
        Order order = pendingOrder();
        order.pay(LocalDateTime.now());

        assertThatThrownBy(order::expire).isInstanceOf(InvalidOrderStatusException.class);
    }

    @Test
    void CANCELED된_주문을_만료하면_예외가_발생한다() {
        Order order = pendingOrder();
        order.cancel();

        assertThatThrownBy(order::expire).isInstanceOf(InvalidOrderStatusException.class);
    }
}
