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

// #56-1에서 낙찰자용 Order(createForWinner), #56-3에서 차순위 수락자용 Order
// (createForBackupAccept)가 추가됐다. 두 팩토리는 필드 구성이 동일해(auction/buyer/purchasePrice/
// shippingFee/paymentDeadline) 실제 생성/검증 로직은 private create()에 있다 - 이름을 분리한
// 이유는 오직 호출부 가독성이다(어느 흐름에서 만들어진 Order인지). paymentDeadline 계산 정책
// (endsAt+24h 대 acceptedAt+24h, §0.10)은 이 팩토리의 책임이 아니다 - 호출자(AuctionSettlementService/
// BackupOfferCommandService)가 이미 계산된 값을 넘긴다.
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

    // 낙찰자용 Order. purchasePrice = auction.finalPrice(FINAL contract §12).
    public static Order createForWinner(
            Auction auction, User buyer, Long purchasePrice, Long shippingFee, LocalDateTime paymentDeadline
    ) {
        return create(auction, buyer, purchasePrice, shippingFee, paymentDeadline);
    }

    // 차순위 수락자용 Order(#56-3, §16). purchasePrice = BackupOffer.purchasePrice(그 후보의
    // myLastBidAmount) - auction.finalPrice가 아니다.
    public static Order createForBackupAccept(
            Auction auction, User buyer, Long purchasePrice, Long shippingFee, LocalDateTime paymentDeadline
    ) {
        return create(auction, buyer, purchasePrice, shippingFee, paymentDeadline);
    }

    // totalAmount는 여기서 purchasePrice+shippingFee로 계산해 호출자가 직접 더하는 곳이 여러
    // 군데로 흩어지지 않게 한다.
    private static Order create(
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

    // FINAL contract §12: PAYMENT_PENDING -> PAYMENT_EXPIRED(scheduler, 기한 초과)만 허용된다.
    // #57-2의 OrderExpirationService가 paymentDeadline < now && status == PAYMENT_PENDING을
    // 먼저 확인한 뒤에만 호출한다 - cancel()/pay()와 동일하게 여기 도달했다는 것 자체가 서비스가
    // 이미 걸렀다는 뜻이라 프로그래밍 오류 가드로만 예외를 던진다.
    public void expire() {
        if (status != OrderStatus.PAYMENT_PENDING) {
            throw new InvalidOrderStatusException(
                    "PAYMENT_PENDING 상태에서만 만료 처리할 수 있습니다. orderId: " + id + ", 현재 상태: " + status
            );
        }
        this.status = OrderStatus.PAYMENT_EXPIRED;
    }

    // FINAL contract §13: PAYMENT_PENDING -> PAID만 여기서 허용한다. PAID 재호출(상태 멱등),
    // PAYMENT_EXPIRED/CANCELED에서의 pay 시도(409/40910·40915)는 서비스가 상태를 먼저 switch로
    // 걸러 도메인 메서드를 아예 호출하지 않는다(OrderCommandService.pay(), AuctionForfeitService의
    // switch 패턴과 동일) - 여기 도달했다는 것 자체가 서비스가 이미 PAYMENT_PENDING임을 확인했다는
    // 뜻이므로, cancel()과 동일하게 프로그래밍 오류 가드로만 InvalidOrderStatusException을 던진다.
    public void pay(LocalDateTime paidAt) {
        if (status != OrderStatus.PAYMENT_PENDING) {
            throw new InvalidOrderStatusException(
                    "PAYMENT_PENDING 상태에서만 결제할 수 있습니다. orderId: " + id + ", 현재 상태: " + status
            );
        }
        this.status = OrderStatus.PAID;
        this.paidAt = paidAt;
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
