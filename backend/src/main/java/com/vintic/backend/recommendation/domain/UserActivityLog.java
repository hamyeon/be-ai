package com.vintic.backend.recommendation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

// 개인화 추천의 근거가 되는 유저 행동 기록.
//
// 연관관계(@ManyToOne) 대신 ID만 저장한다. 이 테이블은 조회 API마다 쓰기가 발생하는데,
// 엔티티를 물고 있으면 로그 하나 남기려고 User/Auction을 조회하게 된다. 추천 계산도
// "유저별 상호작용한 상품 집계" 같은 ID 기반 쿼리라 연관관계가 필요 없다.
//
// auctionId와 productId를 둘 다 저장한다. 추천 대상은 경매지만 취향은 상품(신발) 특성에서
// 나오고, 같은 신발이 여러 번 경매에 올라올 수 있다. 둘 다 있으면 나중에 어느 쪽으로도
// 집계할 수 있다.
@Entity
@Table(
        name = "user_activity_logs",
        indexes = {
                // 유저 벡터 생성: 특정 유저의 최근 행동을 시간순으로 읽는다
                @Index(name = "idx_activity_user_created", columnList = "user_id, created_at"),
                // 인기도 집계: 특정 상품/경매의 행동 수를 센다
                @Index(name = "idx_activity_product", columnList = "product_id"),
                @Index(name = "idx_activity_auction", columnList = "auction_id")
        }
)
public class UserActivityLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "auction_id")
    private Long auctionId;

    @Column(name = "product_id")
    private Long productId;

    @Enumerated(EnumType.STRING)
    @Column(name = "activity_type", nullable = false, length = 20)
    private ActivityType activityType;

    // DWELL 행동에서만 채워진다. 서버가 알 수 없는 값이라 프론트가 보내줘야 하고,
    // 그 연동 전까지는 계속 null이다.
    @Column(name = "dwell_seconds")
    private Integer dwellSeconds;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected UserActivityLog() {
    }

    public static UserActivityLog record(Long userId, Long auctionId, Long productId, ActivityType activityType) {
        return record(userId, auctionId, productId, activityType, null);
    }

    public static UserActivityLog record(
            Long userId,
            Long auctionId,
            Long productId,
            ActivityType activityType,
            Integer dwellSeconds
    ) {
        if (userId == null) {
            throw new IllegalArgumentException("사용자는 필수입니다.");
        }
        if (activityType == null) {
            throw new IllegalArgumentException("행동 유형은 필수입니다.");
        }
        // 어느 쪽을 봤는지 모르면 추천에 쓸 수 없다
        if (auctionId == null && productId == null) {
            throw new IllegalArgumentException("경매 또는 상품 중 하나는 있어야 합니다.");
        }
        if (dwellSeconds != null && dwellSeconds < 0) {
            throw new IllegalArgumentException("체류 시간은 음수일 수 없습니다.");
        }

        UserActivityLog log = new UserActivityLog();
        log.userId = userId;
        log.auctionId = auctionId;
        log.productId = productId;
        log.activityType = activityType;
        log.dwellSeconds = dwellSeconds;
        log.createdAt = LocalDateTime.now();
        return log;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getAuctionId() {
        return auctionId;
    }

    public Long getProductId() {
        return productId;
    }

    public ActivityType getActivityType() {
        return activityType;
    }

    public Integer getDwellSeconds() {
        return dwellSeconds;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
