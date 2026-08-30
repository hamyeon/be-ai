package com.vintic.backend.auction.audit;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.bid.domain.BidType;
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

import java.time.LocalDateTime;

// Event Sourcing이 아니다 - 가격/승자 resolution이 "왜" 그렇게 결정됐는지 추적하기 위한 최소 기록이다.
// 한 사용자 command당 최대 1건만 남는다(Proxy 내부에서 파생된 응찰이 몇 개든 하나로 합쳐서 기록) -
// AuctionPriceAuditRecorder(persistence/application boundary)가 유일한 작성 지점이며, 순수 계산인
// ProxyPriceEngine은 이 엔티티의 존재 자체를 모른다.
// idempotencyId는 raw Idempotency-Key를 복제하지 않고 Idempotency row의 PK만 참조한다(nullable -
// SYSTEM_OPEN처럼 idempotency 키가 없는 트리거를 위해 열어둔다). 이 클래스는 그 row를 조회할 필요가
// 없어 @ManyToOne 연관관계 대신 평범한 컬럼으로만 둔다.
@Entity
@Table(
        name = "auction_price_audits",
        indexes = @Index(name = "idx_auction_price_audit_auction_created", columnList = "auction_id, created_at")
)
public class AuctionPriceAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "auction_id", nullable = false)
    private Auction auction;

    @Column(name = "before_price", nullable = false)
    private Long beforePrice;

    @Column(name = "after_price", nullable = false)
    private Long afterPrice;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resulting_winner_id", nullable = false)
    private User resultingWinner;

    // 컬럼명은 trigger가 아니라 trigger_type이다 - MySQL 8의 예약어(TRIGGER)와 충돌해
    // DDL/INSERT가 구문 오류로 실패한다(실제로 재현 후 확인).
    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false)
    private PriceAuditTrigger trigger;

    @Enumerated(EnumType.STRING)
    @Column(name = "bid_type", nullable = false)
    private BidType bidType;

    @Enumerated(EnumType.STRING)
    @Column(name = "applied_rule", nullable = false)
    private PriceAuditRule appliedRule;

    @Column(name = "idempotency_id")
    private Long idempotencyId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected AuctionPriceAudit() {
    }

    public static AuctionPriceAudit record(
            Auction auction,
            Long beforePrice,
            Long afterPrice,
            User resultingWinner,
            PriceAuditTrigger trigger,
            BidType bidType,
            PriceAuditRule appliedRule,
            Long idempotencyId,
            LocalDateTime createdAt
    ) {
        if (auction == null) {
            throw new IllegalArgumentException("경매는 필수입니다.");
        }
        if (beforePrice == null || afterPrice == null) {
            throw new IllegalArgumentException("beforePrice/afterPrice는 필수입니다.");
        }
        if (resultingWinner == null) {
            throw new IllegalArgumentException("resultingWinner는 필수입니다.");
        }
        if (trigger == null || bidType == null || appliedRule == null) {
            throw new IllegalArgumentException("trigger/bidType/appliedRule은 필수입니다.");
        }

        AuctionPriceAudit audit = new AuctionPriceAudit();
        audit.auction = auction;
        audit.beforePrice = beforePrice;
        audit.afterPrice = afterPrice;
        audit.resultingWinner = resultingWinner;
        audit.trigger = trigger;
        audit.bidType = bidType;
        audit.appliedRule = appliedRule;
        audit.idempotencyId = idempotencyId;
        audit.createdAt = createdAt;
        return audit;
    }

    public Long getId() {
        return id;
    }

    public Auction getAuction() {
        return auction;
    }

    public Long getBeforePrice() {
        return beforePrice;
    }

    public Long getAfterPrice() {
        return afterPrice;
    }

    public User getResultingWinner() {
        return resultingWinner;
    }

    public PriceAuditTrigger getTrigger() {
        return trigger;
    }

    public BidType getBidType() {
        return bidType;
    }

    public PriceAuditRule getAppliedRule() {
        return appliedRule;
    }

    public Long getIdempotencyId() {
        return idempotencyId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
