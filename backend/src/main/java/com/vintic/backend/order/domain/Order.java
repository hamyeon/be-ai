package com.vintic.backend.order.domain;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.common.exception.InvalidOrderStatusException;
import com.vintic.backend.user.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

// #56-1: 낙찰자용 Order 생성만 다룬다(AuctionSettlementService.settle() 전용 진입점).
// 차순위 수락자 Order(#56-2, purchasePrice가 finalPrice가 아니라 그 후보의 myLastBidAmount)는
// 이 팩토리로 만들지 않고 별도 팩토리를 추가할 예정이다 - 지금 미리 그 파라미터를 얹지 않는다.
//
// uk_order_auction_buyer: 같은 (auction, buyer) 조합은 최대 1건만 존재해야 한다는 DB invariant다.
// AuctionSettlementService가 Auction row lock으로 같은 auction에 대한 동시 settle() 호출을
// 이미 직렬화하므로 정상 경로에서는 이 제약에 걸릴 일이 없지만, service-level 사전 조회만으로
// 중복을 보장하지 않는다는 방침(#56-0)에 따라 uk_auction_like_auction_user(#55)/
// uk_auto_bid_setting_active_slot(#41)과 동일한 층위의 최종 방어선으로 둔다.
@Entity
@Table(
        name = "orders",
        uniqueConstraints = @UniqueConstraint(name = "uk_order_auction_buyer", columnNames = {"auction_id", "buyer_id"}),
        indexes = {
                @Index(name = "idx_order_buyer", columnList = "buyer_id")
        }
)
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "auction_id", nullable = false)
    private Auction auction;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "buyer_id", nullable = false)
    private User buyer;

    @Column(nullable = false)
    private Long purchasePrice;

    @Column(nullable = false)
    private Long shippingFee;

    @Column(nullable = false)
    private Long totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Column(nullable = false)
    private LocalDateTime paymentDeadline;

    private LocalDateTime paidAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected Order() {
    }

    // 낙찰자용 Order의 유일한 생성 진입점이다. purchasePrice = auction.finalPrice(FINAL contract
    // §12), totalAmount는 이 팩토리 안에서 purchasePrice+shippingFee로 계산해 호출자가 직접
    // 더하는 곳이 여러 군데로 흩어지지 않게 한다.
    public static Order createForWinner(
            Auction auction, User buyer, Long purchasePrice, Long shippingFee, LocalDateTime paymentDeadline
    ) {
        if (auction == null) {
            throw new IllegalArgumentException("경매는 필수입니다.");
        }
        if (buyer == null) {
            throw new IllegalArgumentException("구매자는 필수입니다.");
        }
        if (purchasePrice == null || purchasePrice <= 0) {
            throw new IllegalArgumentException("구매 금액은 0보다 커야 합니다.");
        }
        if (shippingFee == null || shippingFee < 0) {
            throw new IllegalArgumentException("배송비는 0 이상이어야 합니다.");
        }
        if (paymentDeadline == null) {
            throw new IllegalArgumentException("결제 기한은 필수입니다.");
        }

        Order order = new Order();
        order.auction = auction;
        order.buyer = buyer;
        order.purchasePrice = purchasePrice;
        order.shippingFee = shippingFee;
        order.totalAmount = purchasePrice + shippingFee;
        order.status = OrderStatus.PAYMENT_PENDING;
        order.paymentDeadline = paymentDeadline;
        order.createdAt = LocalDateTime.now();
        return order;
    }

    // FINAL contract §12: PAYMENT_PENDING -> CANCELED(낙찰 포기)만 허용된다. 이 프로젝트에서
    // Order를 CANCELED로 만드는 경로는 forfeit뿐이다(§11) - 다른 상태에서의 취소 시도는 호출자가
    // 이미 걸러야 하는 프로그래밍 오류이므로 여기서 명시적으로 막는다(상태 전이를 domain
    // boundary에서 방어).
    public void cancel() {
        if (status != OrderStatus.PAYMENT_PENDING) {
            throw new InvalidOrderStatusException(
                    "PAYMENT_PENDING 상태에서만 취소할 수 있습니다. orderId: " + id + ", 현재 상태: " + status
            );
        }
        this.status = OrderStatus.CANCELED;
    }

    public Long getId() {
        return id;
    }

    public Auction getAuction() {
        return auction;
    }

    public User getBuyer() {
        return buyer;
    }

    public Long getPurchasePrice() {
        return purchasePrice;
    }

    public Long getShippingFee() {
        return shippingFee;
    }

    public Long getTotalAmount() {
        return totalAmount;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public LocalDateTime getPaymentDeadline() {
        return paymentDeadline;
    }

    public LocalDateTime getPaidAt() {
        return paidAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
