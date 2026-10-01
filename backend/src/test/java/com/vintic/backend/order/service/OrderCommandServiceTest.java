package com.vintic.backend.order.service;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.common.exception.OrderAccessDeniedException;
import com.vintic.backend.common.exception.OrderCanceledException;
import com.vintic.backend.common.exception.OrderNotFoundException;
import com.vintic.backend.common.exception.PaymentExpiredException;
import com.vintic.backend.config.ClockConfig;
import com.vintic.backend.order.domain.Order;
import com.vintic.backend.order.domain.OrderStatus;
import com.vintic.backend.order.dto.OrderPayResponse;
import com.vintic.backend.order.repository.OrderRepository;
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

// FINAL contract §13. Mock 결제 - Order.status 전이만 검증한다. Auction.status 불변은
// 이 서비스가 Auction을 아예 로드하지 않는 것으로 보장된다(코드 자체가 Auction repository를
// 의존하지 않는다).
@DataJpaTest
@Import({OrderCommandService.class, TestClockConfig.class})
class OrderCommandServiceTest {

    private static final LocalDateTime FIXED_NOW = LocalDateTime.ofInstant(TestClockConfig.FIXED_INSTANT, ClockConfig.APP_ZONE);

    @Autowired
    private OrderCommandService orderCommandService;

    @Autowired
    private OrderRepository orderRepository;

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

    private Auction persistEndedAuction(Product product, User winner) {
        Auction auction = Auction.schedule(
                product, 10000L, 5000L, LocalDateTime.now().minusHours(2), LocalDateTime.now().minusHours(1)
        );
        auction.start();
        auction.placeManualBid(winner, 30000L);
        auction.end();
        entityManager.persist(auction);
        return auction;
    }

    private Order persistPendingOrder(Auction auction, User buyer) {
        Order order = orderRepository.save(Order.createForWinner(
                auction, buyer, 30000L, 3000L, auction.getEndAt().plusHours(24)
        ));
        return order;
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void PAYMENT_PENDING_주문을_결제하면_PAID로_전이하고_paidAt이_기록된다() {
        User seller = persistUser("seller@vintic.local");
        User winner = persistUser("winner@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistEndedAuction(product, winner);
        Order order = persistPendingOrder(auction, winner);
        flushAndClear();

        OrderPayResponse response = orderCommandService.pay(order.getId(), winner.getId());

        assertThat(response.status()).isEqualTo(OrderStatus.PAID);
        assertThat(response.paidAt().toLocalDateTime()).isEqualTo(FIXED_NOW);

        Order reloaded = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(reloaded.getPaidAt()).isEqualTo(FIXED_NOW);
    }

    @Test
    void 이미_PAID인_주문에_재호출하면_기존_결과를_그대로_반환한다() {
        User seller = persistUser("seller@vintic.local");
        User winner = persistUser("winner@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistEndedAuction(product, winner);
        Order order = persistPendingOrder(auction, winner);
        flushAndClear();

        OrderPayResponse first = orderCommandService.pay(order.getId(), winner.getId());
        OrderPayResponse second = orderCommandService.pay(order.getId(), winner.getId());

        assertThat(second.status()).isEqualTo(OrderStatus.PAID);
        assertThat(second.paidAt()).isEqualTo(first.paidAt());
    }

    @Test
    void PAYMENT_EXPIRED_주문을_결제하면_예외가_발생한다() {
        User seller = persistUser("seller@vintic.local");
        User winner = persistUser("winner@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistEndedAuction(product, winner);
        Order order = persistPendingOrder(auction, winner);
        ReflectionTestUtils.setField(order, "status", OrderStatus.PAYMENT_EXPIRED);
        entityManager.merge(order);
        flushAndClear();

        assertThatThrownBy(() -> orderCommandService.pay(order.getId(), winner.getId()))
                .isInstanceOf(PaymentExpiredException.class);
    }

    @Test
    void CANCELED된_주문을_결제하면_예외가_발생한다() {
        User seller = persistUser("seller@vintic.local");
        User winner = persistUser("winner@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistEndedAuction(product, winner);
        Order order = persistPendingOrder(auction, winner);
        order.cancel();
        entityManager.merge(order);
        flushAndClear();

        assertThatThrownBy(() -> orderCommandService.pay(order.getId(), winner.getId()))
                .isInstanceOf(OrderCanceledException.class);
    }

    @Test
    void 다른_사용자가_결제하면_예외가_발생한다() {
        User seller = persistUser("seller@vintic.local");
        User winner = persistUser("winner@vintic.local");
        User stranger = persistUser("stranger@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistEndedAuction(product, winner);
        Order order = persistPendingOrder(auction, winner);
        flushAndClear();

        assertThatThrownBy(() -> orderCommandService.pay(order.getId(), stranger.getId()))
                .isInstanceOf(OrderAccessDeniedException.class);
        Order reloaded = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
    }

    @Test
    void 존재하지_않는_주문을_결제하면_예외가_발생한다() {
        assertThatThrownBy(() -> orderCommandService.pay(9999L, 1L))
                .isInstanceOf(OrderNotFoundException.class);
    }
}
