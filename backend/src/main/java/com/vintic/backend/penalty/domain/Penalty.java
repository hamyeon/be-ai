package com.vintic.backend.penalty.domain;

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
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

// FINAL contract §14의 penalty 이력 한 건. noShowCount/bidRestrictedUntil 집계·갱신은 이
// 엔티티의 책임이 아니다 - #56-0 확정대로 이번 범위는 이력 row 저장까지만이고, 그 두 필드의
// 산정 정책은 #57이다(User는 이 엔티티를 몰라도 된다 - User를 건드리지 않는다).
//
// uk_penalty_auction_user_type: 같은 (auction, user)에 같은 type의 penalty가 중복 기록되지
// 않게 하는 DB invariant다. forfeit은 Auction/Order row lock으로 이미 직렬화되므로 정상 경로에서
// 걸릴 일은 없지만, service check만으로 중복을 보장하지 않는다는 방침(#56-0 §8)에 따라 최종
// 방어선으로 둔다.
@Entity
@Table(
        name = "penalties",
        uniqueConstraints = @UniqueConstraint(name = "uk_penalty_auction_user_type", columnNames = {"auction_id", "user_id", "type"}),
        indexes = {
                @Index(name = "idx_penalty_user", columnList = "user_id")
        }
)
public class Penalty {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "auction_id", nullable = false)
    private Auction auction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PenaltyType type;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected Penalty() {
    }

    public static Penalty forfeited(User user, Auction auction) {
        if (user == null) {
            throw new IllegalArgumentException("사용자는 필수입니다.");
        }
        if (auction == null) {
            throw new IllegalArgumentException("경매는 필수입니다.");
        }

        Penalty penalty = new Penalty();
        penalty.user = user;
        penalty.auction = auction;
        penalty.type = PenaltyType.FORFEITED;
        penalty.createdAt = LocalDateTime.now();
        return penalty;
    }

    // #57-2: 결제 기한 만료 penalty. forfeited()와 필드 구성이 동일하고 type만 다르다 -
    // noShowCount/bidRestrictedUntil 갱신은 이 엔티티가 아니라 호출자(OrderExpirationService)가
    // User.recordPaymentExpiredPenalty()로 별도 처리한다(사용자 확정: FORFEITED는 noShowCount에
    // 반영하지 않는다 - forfeited()는 User를 여전히 건드리지 않는다).
    public static Penalty paymentExpired(User user, Auction auction) {
        if (user == null) {
            throw new IllegalArgumentException("사용자는 필수입니다.");
        }
        if (auction == null) {
            throw new IllegalArgumentException("경매는 필수입니다.");
        }

        Penalty penalty = new Penalty();
        penalty.user = user;
        penalty.auction = auction;
        penalty.type = PenaltyType.PAYMENT_EXPIRED;
        penalty.createdAt = LocalDateTime.now();
        return penalty;
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public Auction getAuction() {
        return auction;
    }

    public PenaltyType getType() {
        return type;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
