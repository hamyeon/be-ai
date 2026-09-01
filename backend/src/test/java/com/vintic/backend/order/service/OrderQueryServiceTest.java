package com.vintic.backend.order.service;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.common.exception.OrderAccessDeniedException;
import com.vintic.backend.common.exception.OrderNotFoundException;
import com.vintic.backend.config.ClockConfig;
import com.vintic.backend.order.domain.Order;
import com.vintic.backend.order.domain.OrderStatus;
import com.vintic.backend.order.dto.OrderResponse;
import com.vintic.backend.order.repository.OrderRepository;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// FINAL contract §12.
@DataJpaTest
@Import({OrderQueryService.class, TestClockConfig.class})
class OrderQueryServiceTest {

    private static final LocalDateTime FIXED_NOW = LocalDateTime.ofInstant(TestClockConfig.FIXED_INSTANT, ClockConfig.APP_ZONE);

    @Autowired
    private OrderQueryService orderQueryService;

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

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void 소유자가_조회하면_FINAL_contract_필드를_전부_반환한다() {
        User seller = persistUser("seller@vintic.local");
        User winner = persistUser("winner@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistEndedAuction(product, winner);
        Order order = orderRepository.save(Order.createForWinner(
                auction, winner, 30000L, 3000L, auction.getEndAt().plusHours(24)
        ));
        flushAndClear();

        OrderResponse response = orderQueryService.getOrder(order.getId(), winner.getId());

        assertThat(response.orderId()).isEqualTo(order.getId());
        assertThat(response.auctionId()).isEqualTo(auction.getId());
        assertThat(response.status()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(response.product().productId()).isEqualTo(product.getId());
        assertThat(response.purchasePrice()).isEqualTo(30000L);
        assertThat(response.shippingFee()).isEqualTo(3000L);
        assertThat(response.totalAmount()).isEqualTo(33000L);
        assertThat(response.serverTime().toLocalDateTime()).isEqualTo(FIXED_NOW);
        assertThat(response.paidAt()).isNull();
    }

    @Test
    void 결제_완료된_주문은_paidAt을_반환한다() {
        User seller = persistUser("seller@vintic.local");
        User winner = persistUser("winner@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistEndedAuction(product, winner);
        Order order = Order.createForWinner(auction, winner, 30000L, 3000L, auction.getEndAt().plusHours(24));
        order.pay(LocalDateTime.now().minusMinutes(5));
        orderRepository.save(order);
        flushAndClear();

        OrderResponse response = orderQueryService.getOrder(order.getId(), winner.getId());

        assertThat(response.status()).isEqualTo(OrderStatus.PAID);
        assertThat(response.paidAt()).isNotNull();
    }

    @Test
    void 다른_사용자의_주문을_조회하면_예외가_발생한다() {
        User seller = persistUser("seller@vintic.local");
        User winner = persistUser("winner@vintic.local");
        User stranger = persistUser("stranger@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistEndedAuction(product, winner);
        Order order = orderRepository.save(Order.createForWinner(
                auction, winner, 30000L, 3000L, auction.getEndAt().plusHours(24)
        ));
        flushAndClear();

        assertThatThrownBy(() -> orderQueryService.getOrder(order.getId(), stranger.getId()))
                .isInstanceOf(OrderAccessDeniedException.class);
    }

    @Test
    void 존재하지_않는_주문을_조회하면_예외가_발생한다() {
        assertThatThrownBy(() -> orderQueryService.getOrder(9999L, 1L))
                .isInstanceOf(OrderNotFoundException.class);
    }
}
