package com.vintic.backend.like.domain;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.user.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

// #55: 관심 상품(찜) row는 (auction, user) 조합당 최대 1개만 존재해야 한다. Service의
// exists-check(사전 조회)만으로는 동시 요청에서 중복 생성을 막을 수 없으므로,
// uk_auction_like_auction_user UNIQUE 제약을 최종 방어선으로 둔다 - AutoBidSetting의
// active-slot UNIQUE(#41)와 동일한 방어 계층 구조다.
@Entity
@Table(
        name = "auction_likes",
        uniqueConstraints = @UniqueConstraint(name = "uk_auction_like_auction_user", columnNames = {"auction_id", "user_id"}),
        indexes = {
                @Index(name = "idx_auction_like_auction", columnList = "auction_id")
        }
)
public class AuctionLike {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "auction_id", nullable = false)
    private Auction auction;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected AuctionLike() {
    }

    public static AuctionLike create(Auction auction, User user) {
        if (auction == null) {
            throw new IllegalArgumentException("경매는 필수입니다.");
        }
        if (user == null) {
            throw new IllegalArgumentException("사용자는 필수입니다.");
        }
        AuctionLike like = new AuctionLike();
        like.auction = auction;
        like.user = user;
        like.createdAt = LocalDateTime.now();
        return like;
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

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
