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
//
// activeSlot(#41): RESERVED/ACTIVE/CAP_REACHED는 항상 true, CANCELED는 항상 null이다.
// status와 activeSlot이 어긋나면 DB 제약(uk_auto_bid_setting_active_slot)의 의미가 깨지므로
// 반드시 이 클래스의 상태 전이 메서드 안에서만 함께 바꾼다 - Service가 둘을 따로 건드리지 않는다.
// MySQL의 UNIQUE는 NULL을 서로 다른 값으로 취급하므로, CANCELED 이력은 여러 건 남을 수 있지만
// "현재 설정"(non-null activeSlot)은 (auction, user) 조합당 최대 1건으로 DB가 직접 보장한다.
@Entity
@Table(
        name = "auto_bid_settings",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_auto_bid_setting_active_slot",
                columnNames = {"auction_id", "user_id", "active_slot"}
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

    @Column(name = "active_slot")
    private Boolean activeSlot;

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
        setting.activeSlot = Boolean.TRUE;
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
        this.activeSlot = Boolean.TRUE;
        this.updatedAt = LocalDateTime.now();
    }

    // maxAmount에 도달해 더 이상 자동 경쟁 입찰을 할 수 없는 상태로 전환한다.
    // RESERVED에서도 발생할 수 있다 — 경매 시작 전 이미 currentPrice가 maxAmount를 넘어선 경우.
    public void markCapReached() {
        if (status != AutoBidSettingStatus.RESERVED && status != AutoBidSettingStatus.ACTIVE) {
            throw new InvalidAutoBidSettingStatusException(
                    "RESERVED/ACTIVE 상태에서만 상한 도달 처리를 할 수 있습니다. 현재 상태: " + status
            );
        }
        this.status = AutoBidSettingStatus.CAP_REACHED;
        this.activeSlot = Boolean.TRUE;
        this.updatedAt = LocalDateTime.now();
    }

    // maxAmount 상향 등으로 다시 경쟁 입찰이 가능해졌을 때 사용한다.
    // 이번 범위에서는 전이 규칙만 제공하며, maxAmount 자체를 바꾸는 로직은 포함하지 않는다.
    public void reactivateAfterCapIncrease() {
        if (status != AutoBidSettingStatus.CAP_REACHED) {
            throw new InvalidAutoBidSettingStatusException(
                    "CAP_REACHED 상태에서만 재활성화할 수 있습니다. 현재 상태: " + status
            );
        }
        this.status = AutoBidSettingStatus.ACTIVE;
        this.activeSlot = Boolean.TRUE;
        this.updatedAt = LocalDateTime.now();
    }

    public void cancel() {
        if (status != AutoBidSettingStatus.RESERVED
                && status != AutoBidSettingStatus.ACTIVE
                && status != AutoBidSettingStatus.CAP_REACHED) {
            throw new InvalidAutoBidSettingStatusException(
                    "RESERVED/ACTIVE/CAP_REACHED 상태에서만 취소할 수 있습니다. 현재 상태: " + status
            );
        }
        this.status = AutoBidSettingStatus.CANCELED;
        this.activeSlot = null;
        this.updatedAt = LocalDateTime.now();
    }

    // PATCH 전용. 상한가 자체의 상향/하향/동일값 허용 여부(RESERVED vs ACTIVE/CAP_REACHED)는
    // Auction의 minCapAmount/기존 maxAmount와 비교해야 판단할 수 있어 Service에서 검증한 뒤
    // 호출한다 - 이 메서드는 값이 이미 유효하다는 전제로 반영만 한다.
    public void changeMaxAmount(Long newMaxAmount) {
        if (newMaxAmount == null || newMaxAmount <= 0) {
            throw new IllegalArgumentException("최대 입찰 금액은 0보다 커야 합니다.");
        }
        this.maxAmount = newMaxAmount;
        this.updatedAt = LocalDateTime.now();
    }

    // #42: 계산식 자체는 EffectiveCapCalculator 하나로 통일했다(ProxyPriceEngine도 같은 공식을
    // 쓴다) - 이 메서드는 entity 필드(maxAmount)를 넘겨주는 얇은 위임일 뿐이다.
    public Long getEffectiveCap(Long currentPrice, Long bidIncrement) {
        return EffectiveCapCalculator.calculate(maxAmount, currentPrice, bidIncrement);
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

    public Boolean getActiveSlot() {
        return activeSlot;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
