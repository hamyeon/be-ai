package com.vintic.backend.autobid.domain;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.common.exception.InvalidAutoBidSettingStatusException;
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

// 자동입찰 "실행"(경쟁 입찰 생성)과 경매 시작 시 RESERVED->ACTIVE 일괄 전환 스케줄러는 이번 범위가 아니다.
// 여기서는 상태 전이 메서드만 제공한다. CANCELED는 향후 자동입찰 수행을 중단한다는 뜻이며
// 이미 생성된 Bid를 취소하는 의미는 아니다.
@Entity
@Table(
        name = "auto_bid_settings",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_auto_bid_setting_auction_user",
                columnNames = {"auction_id", "user_id"}
        ),
        indexes = {
                @Index(name = "idx_auto_bid_setting_auction_status", columnList = "auction_id, status"),
                @Index(name = "idx_auto_bid_setting_user", columnList = "user_id")
        }
)
public class AutoBidSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "auction_id", nullable = false)
    private Auction auction;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "max_amount", nullable = false)
    private Long maxAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AutoBidSettingStatus status;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    protected AutoBidSetting() {
    }

    public static AutoBidSetting reserve(Auction auction, User user, Long maxAmount) {
        if (auction == null) {
            throw new IllegalArgumentException("경매는 필수입니다.");
        }
        if (user == null) {
            throw new IllegalArgumentException("사용자는 필수입니다.");
        }
        if (maxAmount == null || maxAmount <= 0) {
            throw new IllegalArgumentException("최대 입찰 금액은 0보다 커야 합니다.");
        }

        AutoBidSetting setting = new AutoBidSetting();
        setting.auction = auction;
        setting.user = user;
        setting.maxAmount = maxAmount;
        setting.status = AutoBidSettingStatus.RESERVED;
        LocalDateTime now = LocalDateTime.now();
        setting.createdAt = now;
        setting.updatedAt = now;
        return setting;
    }

    public void activate() {
        if (status != AutoBidSettingStatus.RESERVED) {
            throw new InvalidAutoBidSettingStatusException(
                    "RESERVED 상태에서만 활성화할 수 있습니다. 현재 상태: " + status
            );
        }
        this.status = AutoBidSettingStatus.ACTIVE;
        this.updatedAt = LocalDateTime.now();
    }

    public void exhaust() {
        if (status != AutoBidSettingStatus.ACTIVE) {
            throw new InvalidAutoBidSettingStatusException(
                    "ACTIVE 상태에서만 소진 처리할 수 있습니다. 현재 상태: " + status
            );
        }
        this.status = AutoBidSettingStatus.EXHAUSTED;
        this.updatedAt = LocalDateTime.now();
    }

    public void cancel() {
        if (status != AutoBidSettingStatus.RESERVED && status != AutoBidSettingStatus.ACTIVE) {
            throw new InvalidAutoBidSettingStatusException(
                    "RESERVED/ACTIVE 상태에서만 취소할 수 있습니다. 현재 상태: " + status
            );
        }
        this.status = AutoBidSettingStatus.CANCELED;
        this.updatedAt = LocalDateTime.now();
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

    public Long getMaxAmount() {
        return maxAmount;
    }

    public AutoBidSettingStatus getStatus() {
        return status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
