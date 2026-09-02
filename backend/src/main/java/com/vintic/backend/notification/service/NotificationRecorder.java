package com.vintic.backend.notification.service;

import com.vintic.backend.notification.domain.Notification;
import com.vintic.backend.notification.domain.NotificationType;
import com.vintic.backend.notification.repository.NotificationRepository;
import com.vintic.backend.user.domain.User;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

// persistence/application boundary 전용(AuctionPriceAuditRecorder와 동일한 성격) - 기존 lifecycle
// 서비스(AuctionSettlementService/AuctionForfeitService/BackupOfferCommandService/
// BackupOfferExpirationService/OrderExpirationService)의 @Transactional 메서드 안에서 호출되어
// 그 트랜잭션에 그대로 참여한다(새 트랜잭션을 열지 않는다) - Notification insert가 실패하면
// 호출자의 lifecycle transaction도 함께 롤백된다.
//
// #75 시점에는 이 클래스를 실제로 호출하는 지점이 아직 없다(lifecycle 연결은 다음 단계).
//
// businessEventKey = "{TYPE}:{resourceId}" - resourceId는 이벤트를 발생시킨 소스 엔티티
// 자신의 PK(Order.id/BackupOffer.id)라 항상 새로 생성된 값이다. 이 값을 만드는 세 지점 모두
// 이미 Auction row lock + 소스 엔티티 자신의 UNIQUE 제약으로 "한 번만 생성/전이"가 보장되므로,
// 여기서는 UNIQUE 위반을 사전에 catch하지 않고 그대로 propagate한다(Notification.businessEventKey
// UNIQUE 제약이 최종 방어선, 별도 claim/retry 없음).
//
// title/body는 상품명 등을 담지 않는 3종 고정 정적 문구다 - 이를 위해 추가 조회를 하지 않는다.
@Component
public class NotificationRecorder {

    private final NotificationRepository notificationRepository;
    private final Clock clock;

    public NotificationRecorder(NotificationRepository notificationRepository, Clock clock) {
        this.notificationRepository = notificationRepository;
        this.clock = clock;
    }

    public Notification record(User recipient, NotificationType type, Long auctionId, Long resourceId) {
        String businessEventKey = type.name() + ":" + resourceId;
        Notification notification = Notification.create(
                recipient, type, auctionId, resourceId,
                title(type), body(type), businessEventKey, LocalDateTime.now(clock)
        );
        return notificationRepository.save(notification);
    }

    private String title(NotificationType type) {
        return switch (type) {
            case AUCTION_WON -> "낙찰되었습니다";
            case BACKUP_OFFER_CREATED -> "차순위 구매 제안이 도착했습니다";
            case PAYMENT_EXPIRED -> "결제 기한이 만료되었습니다";
        };
    }

    private String body(NotificationType type) {
        return switch (type) {
            case AUCTION_WON -> "낙찰되었습니다. 결제를 진행해주세요.";
            case BACKUP_OFFER_CREATED -> "차순위 구매 제안이 도착했습니다. 24시간 이내에 응답해주세요.";
            case PAYMENT_EXPIRED -> "결제 기한이 만료되어 차순위에게 구매 기회가 넘어갑니다.";
        };
    }
}
