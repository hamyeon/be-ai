package com.vintic.backend.order.service;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.domain.AuctionStatus;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.common.exception.AuctionNotFoundException;
import com.vintic.backend.common.exception.InvalidAuctionStatusException;
import com.vintic.backend.order.domain.Order;
import com.vintic.backend.order.domain.OrderStatus;
import com.vintic.backend.order.repository.OrderRepository;
import com.vintic.backend.product.domain.Product;
import com.vintic.backend.user.domain.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

// #56-1: AuctionSettlementService.settle() - 낙찰자 Order 생성 command.
@DataJpaTest
@Import(AuctionSettlementService.class)
class AuctionSettlementServiceTest {

    @Autowired
    private AuctionSettlementService auctionSettlementService;

    @Autowired
    private AuctionRepository auctionRepository;

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

    private Auction persistEndedAuctionWithWinner(Product product, User winner, Long finalPrice) {
        Auction auction = Auction.schedule(
                product, 10000L, 5000L, LocalDateTime.now().minusHours(2), LocalDateTime.now().minusHours(1)
        );
        auction.start();
        auction.placeManualBid(winner, finalPrice);
        auction.end();
        entityManager.persist(auction);
        return auction;
    }

    private Auction persistEndedAuctionWithoutBids(Product product) {
        Auction auction = Auction.schedule(
                product, 10000L, 5000L, LocalDateTime.now().minusHours(2), LocalDateTime.now().minusHours(1)
        );
        auction.start();
        auction.end();
        entityManager.persist(auction);
        return auction;
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void 낙찰자가_있는_ENDED_경매를_settle하면_PAYMENT_PENDING_Order가_생성된다() {
        User seller = persistUser("seller@vintic.local");
        User winner = persistUser("winner@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistEndedAuctionWithWinner(product, winner, 30000L);
        flushAndClear();

        Optional<Order> created = auctionSettlementService.settle(auction.getId());

        assertThat(created).isPresent();
        Order order = created.get();
        assertThat(order.getId()).isNotNull();
        assertThat(order.getBuyer().getId()).isEqualTo(winner.getId());
        assertThat(order.getPurchasePrice()).isEqualTo(30000L);
        assertThat(order.getShippingFee()).isEqualTo(3000L);
        assertThat(order.getTotalAmount()).isEqualTo(33000L);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        // paymentDeadline = auction.endsAt + 24h (FINAL contract §0.10). H2 컬럼이 nanosecond
        // 정밀도를 그대로 보존하지 않아(마이크로초로 절삭) 초 단위 근접만 확인한다 - 이미 코드베이스
        // 전반에서 쓰는 패턴(AuctionQueryServiceTest의 endsAt/bidRestrictedUntil 비교와 동일).
        assertThat(order.getPaymentDeadline()).isCloseTo(auction.getEndAt().plusHours(24), within(1, ChronoUnit.SECONDS));
        assertThat(ChronoUnit.SECONDS.between(order.getCreatedAt(), LocalDateTime.now())).isLessThan(5);
    }

    @Test
    void 입찰이_없는_ENDED_경매를_settle해도_Order를_생성하지_않는다() {
        User seller = persistUser("seller@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistEndedAuctionWithoutBids(product);
        flushAndClear();

        Optional<Order> result = auctionSettlementService.settle(auction.getId());

        assertThat(result).isEmpty();
        assertThat(orderRepository.count()).isZero();
    }

    @Test
    void 이미_settle된_경매를_다시_settle해도_Order가_중복_생성되지_않는다() {
        User seller = persistUser("seller@vintic.local");
        User winner = persistUser("winner@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistEndedAuctionWithWinner(product, winner, 30000L);
        flushAndClear();

        Optional<Order> first = auctionSettlementService.settle(auction.getId());
        Optional<Order> second = auctionSettlementService.settle(auction.getId());

        assertThat(first).isPresent();
        assertThat(second).isPresent();
        assertThat(second.get().getId()).isEqualTo(first.get().getId());
        assertThat(orderRepository.count()).isEqualTo(1);
    }

    @Test
    void ENDED가_아닌_경매는_settle할_수_없다() {
        User seller = persistUser("seller@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = Auction.schedule(
                product, 10000L, 5000L, LocalDateTime.now().minusMinutes(30), LocalDateTime.now().plusHours(1)
        );
        auction.start();
        entityManager.persist(auction);
        flushAndClear();
        assertThat(auction.getStatus()).isEqualTo(AuctionStatus.LIVE);

        assertThatThrownBy(() -> auctionSettlementService.settle(auction.getId()))
                .isInstanceOf(InvalidAuctionStatusException.class);
        assertThat(orderRepository.count()).isZero();
    }

    @Test
    void 존재하지_않는_경매를_settle하면_예외가_발생한다() {
        assertThatThrownBy(() -> auctionSettlementService.settle(9999L))
                .isInstanceOf(AuctionNotFoundException.class);
    }
}
