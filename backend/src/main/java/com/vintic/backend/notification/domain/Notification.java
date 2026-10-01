package com.vintic.backend.notification.domain;

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

// #75. auction/resource는 화면 이동용 ID만 필요해 JPA 연관관계를 맺지 않는다(AuctionPriceAudit.
// idempotencyId와 동일한 "참조값만, 연관관계 없음" 패턴) - Auction domain behavior가 이 엔티티에
// 필요하지 않다.
//
// businessEventKey("{TYPE}:{sourceEntityId}", 예: "AUCTION_WON:55")가 유일한 중복 방어선이다.
// 소스 엔티티(Order/BackupOffer) 자체가 이미 Auction row lock + 자신의 UNIQUE 제약으로 "한 번만
// 생성/전이"됨이 보장된 지점에서만 기록하므로, 이 UNIQUE 위반은 정상 경로에서 발생을 기대하지
// 않는다 - 그래도 application pre-check만으로 끝내지 않기 위한 DB 최종 방어선으로 둔다(#75
// 사용자 확정, 별도 claim/retry 시스템을 만들지 않는다).
@Entity
@Table(
        name = "notifications",
        uniqueConstraints = @UniqueConstraint(name = "uk_notification_business_event_key", columnNames = "business_event_key"),
        indexes = {
                @Index(name = "idx_notification_recipient_created", columnList = "recipient_id, created_at, id"),
                @Index(name = "idx_notification_recipient_read", columnList = "recipient_id, read_at")
        }
)
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recipient_id", nullable = false)
    private User recipient;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationType type;

    @Column(name = "auction_id", nullable = false)
    private Long auctionId;

    @Column(name = "resource_id", nullable = false)
    private Long resourceId;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String body;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    @Column(name = "business_event_key", nullable = false)
    private String businessEventKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected Notification() {
    }

    public static Notification create(
            User recipient,
            NotificationType type,
            Long auctionId,
            Long resourceId,
            String title,
            String body,
            String businessEventKey,
            LocalDateTime createdAt
    ) {
        if (recipient == null) {
            throw new IllegalArgumentException("수신자는 필수입니다.");
        }
        if (type == null) {
            throw new IllegalArgumentException("type은 필수입니다.");
        }
        if (auctionId == null) {
            throw new IllegalArgumentException("auctionId는 필수입니다.");
        }
        if (resourceId == null) {
            throw new IllegalArgumentException("resourceId는 필수입니다.");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title은 필수입니다.");
        }
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("body는 필수입니다.");
        }
        if (businessEventKey == null || businessEventKey.isBlank()) {
            throw new IllegalArgumentException("businessEventKey는 필수입니다.");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt은 필수입니다.");
        }

        Notification notification = new Notification();
        notification.recipient = recipient;
        notification.type = type;
        notification.auctionId = auctionId;
        notification.resourceId = resourceId;
        notification.title = title;
        notification.body = body;
        notification.businessEventKey = businessEventKey;
        notification.createdAt = createdAt;
        return notification;
    }

    // PATCH /notifications/{id}/read 재호출 idempotent - 이미 읽은 알림은 readAt을 덮어쓰지
    // 않는다(나중 시각으로 갱신되는 것을 방지).
    public void markRead(LocalDateTime readAt) {
        if (this.readAt == null) {
            this.readAt = readAt;
        }
    }

    public boolean isUnread() {
        return readAt == null;
    }

    public Long getId() {
        return id;
    }

    public User getRecipient() {
        return recipient;
    }

    public NotificationType getType() {
        return type;
    }

    public Long getAuctionId() {
        return auctionId;
    }

    public Long getResourceId() {
        return resourceId;
    }

    public String getTitle() {
        return title;
    }

    public String getBody() {
        return body;
    }

    public LocalDateTime getReadAt() {
        return readAt;
    }

    public String getBusinessEventKey() {
        return businessEventKey;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
