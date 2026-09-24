package com.vintic.backend.purchasegoal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

// ListingMatcher.evaluate()의 판정 결과(MatchResult)를 (goal, auction) 단위로 저장하는 구조만
// 담는다 - ListingMatcher 인터페이스 주석("결과는 (goal, auction) 단위로 저장해 재호출하지
// 않는다")이 백엔드에 위임한 요구사항이다. 실제로 이 테이블에 쓰고 읽는 매칭/캐시 로직(Day 4)은
// 여기 없다 - goalId/auctionId 모두 Notification.auctionId와 같은 "참조값만, 연관관계 없음"
// 패턴이라 PurchaseGoal/Auction 엔티티에 대한 연관관계를 맺지 않는다.
@Entity
@Table(
        name = "purchase_goal_matches",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_purchase_goal_match_goal_auction",
                columnNames = {"goal_id", "auction_id"}
        )
)
public class PurchaseGoalMatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "goal_id", nullable = false)
    private Long goalId;

    @Column(name = "auction_id", nullable = false)
    private Long auctionId;

    @Column(nullable = false)
    private boolean matched;

    @Column(name = "semantic_score", nullable = false)
    private double semanticScore;

    @Column(length = 1000)
    private String reason;

    @Column(name = "listing_model_key")
    private String listingModelKey;

    @Column(name = "evaluated_at", nullable = false, updatable = false)
    private LocalDateTime evaluatedAt;

    protected PurchaseGoalMatch() {
    }

    public static PurchaseGoalMatch create(
            Long goalId,
            Long auctionId,
            boolean matched,
            double semanticScore,
            String reason,
            String listingModelKey,
            LocalDateTime evaluatedAt
    ) {
        if (goalId == null) {
            throw new IllegalArgumentException("goalId는 필수입니다.");
        }
        if (auctionId == null) {
            throw new IllegalArgumentException("auctionId는 필수입니다.");
        }
        if (evaluatedAt == null) {
            throw new IllegalArgumentException("evaluatedAt은 필수입니다.");
        }

        PurchaseGoalMatch match = new PurchaseGoalMatch();
        match.goalId = goalId;
        match.auctionId = auctionId;
        match.matched = matched;
        match.semanticScore = semanticScore;
        match.reason = reason;
        match.listingModelKey = listingModelKey;
        match.evaluatedAt = evaluatedAt;
        return match;
    }

    public Long getId() {
        return id;
    }

    public Long getGoalId() {
        return goalId;
    }

    public Long getAuctionId() {
        return auctionId;
    }

    public boolean isMatched() {
        return matched;
    }

    public double getSemanticScore() {
        return semanticScore;
    }

    public String getReason() {
        return reason;
    }

    public String getListingModelKey() {
        return listingModelKey;
    }

    public LocalDateTime getEvaluatedAt() {
        return evaluatedAt;
    }
}
