package com.vintic.backend.auction.domain;

import com.vintic.backend.common.exception.InvalidAuctionStatusException;
import com.vintic.backend.product.domain.Product;
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
import jakarta.persistence.Version;

import java.time.LocalDateTime;

// current_winner_id는 LIVE 중 입찰 갱신 트랜잭션에서만 채워지며, 종료 시점 값을 그대로 고정(freeze)한다.
// 이번 범위에는 입찰 처리 자체가 없어 항상 null로 유지된다(입찰자 없이 종료되는 경우도 ENDED + winner null로 표현).
@Entity
@Table(
        name = "auctions",
        indexes = {
                @Index(name = "idx_auction_product", columnList = "product_id"),
                @Index(name = "idx_auction_status_end_at", columnList = "status, end_at"),
                @Index(name = "idx_auction_status_start_at", columnList = "status, start_at"),
                @Index(name = "idx_auction_current_winner", columnList = "current_winner_id")
        }
)
public class Auction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "current_winner_id")
    private User currentWinner;

    @Column(nullable = false)
    private Long startPrice;

    @Column(nullable = false)
    private Long currentPrice;

    @Column(nullable = false)
    private Long bidIncrement;

    @Column(nullable = false)
    private LocalDateTime startAt;

    @Column(nullable = false)
    private LocalDateTime endAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AuctionStatus status;

    @Version
    private Long version;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected Auction() {
    }

    public static Auction schedule(
            Product product,
            Long startPrice,
            Long bidIncrement,
            LocalDateTime startAt,
            LocalDateTime endAt
    ) {
        if (product == null) {
            throw new IllegalArgumentException("상품은 필수입니다.");
        }
        if (startPrice == null || startPrice <= 0) {
            throw new IllegalArgumentException("시작가는 0보다 커야 합니다.");
        }
        if (bidIncrement == null || bidIncrement <= 0) {
            throw new IllegalArgumentException("입찰 단위는 0보다 커야 합니다.");
        }
        if (startAt == null || endAt == null || !endAt.isAfter(startAt)) {
            throw new IllegalArgumentException("종료 시각은 시작 시각보다 이후여야 합니다.");
        }

        Auction auction = new Auction();
        auction.product = product;
        auction.startPrice = startPrice;
        auction.currentPrice = startPrice;
        auction.bidIncrement = bidIncrement;
        auction.startAt = startAt;
        auction.endAt = endAt;
        auction.status = AuctionStatus.SCHEDULED;
        auction.createdAt = LocalDateTime.now();
        return auction;
    }

    public void start() {
        if (status != AuctionStatus.SCHEDULED) {
            throw new InvalidAuctionStatusException(
                    "SCHEDULED 상태에서만 경매를 시작할 수 있습니다. 현재 상태: " + status
            );
        }
        this.status = AuctionStatus.LIVE;
    }

    public void end() {
        if (status != AuctionStatus.LIVE) {
            throw new InvalidAuctionStatusException(
                    "LIVE 상태에서만 경매를 종료할 수 있습니다. 현재 상태: " + status
            );
        }
        this.status = AuctionStatus.ENDED;
    }

    public void cancel() {
        if (status != AuctionStatus.SCHEDULED) {
            throw new InvalidAuctionStatusException(
                    "SCHEDULED 상태에서만 경매를 취소할 수 있습니다. 현재 상태: " + status
            );
        }
        this.status = AuctionStatus.CANCELED;
    }

    public Long getId() {
        return id;
    }

    public Product getProduct() {
        return product;
    }

    public User getCurrentWinner() {
        return currentWinner;
    }

    public Long getStartPrice() {
        return startPrice;
    }

    public Long getCurrentPrice() {
        return currentPrice;
    }

    public Long getBidIncrement() {
        return bidIncrement;
    }

    public LocalDateTime getStartAt() {
        return startAt;
    }

    public LocalDateTime getEndAt() {
        return endAt;
    }

    public AuctionStatus getStatus() {
        return status;
    }

    public Long getVersion() {
        return version;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
