package com.vintic.backend.bid.domain;

import com.vintic.backend.auction.domain.Auction;
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
import org.hibernate.annotations.Check;

import java.time.LocalDateTime;

// 실제 입찰 생성 API/서비스는 이번 범위가 아니다. place()는 향후 입찰 처리 로직과
// 테스트가 사용할 최소 생성 진입점이다.
@Entity
@Table(
        name = "bids",
        indexes = {
                @Index(name = "idx_bid_auction_amount", columnList = "auction_id, amount"),
                @Index(name = "idx_bid_auction_created_id", columnList = "auction_id, created_at, id"),
                @Index(name = "idx_bid_user", columnList = "user_id")
        }
)
@Check(name = "chk_bid_amount_positive", constraints = "amount > 0")
public class Bid {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "auction_id", nullable = false)
    private Auction auction;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private Long amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "bid_type", nullable = false)
    private BidType bidType;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected Bid() {
    }

    public static Bid place(Auction auction, User user, Long amount, BidType bidType) {
        if (auction == null) {
            throw new IllegalArgumentException("경매는 필수입니다.");
        }
        if (user == null) {
            throw new IllegalArgumentException("입찰자는 필수입니다.");
        }
        if (amount == null || amount <= 0) {
            throw new IllegalArgumentException("입찰 금액은 0보다 커야 합니다.");
        }
        if (bidType == null) {
            throw new IllegalArgumentException("입찰 타입은 필수입니다.");
        }

        Bid bid = new Bid();
        bid.auction = auction;
        bid.user = user;
        bid.amount = amount;
        bid.bidType = bidType;
        bid.createdAt = LocalDateTime.now();
        return bid;
    }

    public Long getId() {
        return id;
    }

    public Auction getAuction() {
        return auction;
    }

    public User getUser() {
        return user;
    }

    public Long getAmount() {
        return amount;
    }

    public BidType getBidType() {
        return bidType;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
