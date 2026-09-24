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
        return save(recipient, type, auctionId, resourceId, businessEventKey, title(type), body(type));
    }

    // Purchase Agent 전용(#Day7). 기존 record()의 "TYPE:resourceId" 키로는 같은 Goal이 여러
    // 경매에 순차 참여하며 매번 ENGAGED/LOST를 만드는 상황을 구분할 수 없다 - auctionId까지 키에
    // 포함해야 (goal, auction) 쌍마다 독립된 알림이 된다. resourceId는 다른 타입과 동일하게
    // "화면 이동용 ID" 역할이며 여기서는 Goal 상세로 이동하도록 goalId를 쓴다.
    public Notification recordPurchaseAgentEngaged(User recipient, Long goalId, Long auctionId) {
        String businessEventKey = NotificationType.PURCHASE_AGENT_ENGAGED.name() + ":" + goalId + ":" + auctionId;
        return save(
                recipient, NotificationType.PURCHASE_AGENT_ENGAGED, auctionId, goalId, businessEventKey,
                "구매 대행이 입찰을 시작했습니다", "설정하신 조건에 맞는 경매를 찾아 자동입찰을 등록했습니다."
        );
    }

    // stillSearching=true(ACTIVE로 복귀, deadline 전)와 false(EXPIRED/CANCELLED로 종료)는
    // 문구를 구분한다 - 계속 찾는 중인지, 이제 끝났는지는 사용자가 다음에 뭘 해야 할지가 다르다.
    public Notification recordPurchaseAgentLost(User recipient, Long goalId, Long auctionId, boolean stillSearching) {
        String businessEventKey = NotificationType.PURCHASE_AGENT_LOST.name() + ":" + goalId + ":" + auctionId;
        String body = stillSearching
                ? "이번 경매는 낙찰하지 못했습니다. 계속해서 조건에 맞는 경매를 찾고 있습니다."
                : "이번 경매는 낙찰하지 못했습니다. 더 이상 참여할 수 있는 경매가 없어 구매 대행을 종료합니다.";
        return save(
                recipient, NotificationType.PURCHASE_AGENT_LOST, auctionId, goalId, businessEventKey,
                "구매 대행이 낙찰하지 못했습니다", body
        );
    }

    private Notification save(
            User recipient, NotificationType type, Long auctionId, Long resourceId,
            String businessEventKey, String title, String body
    ) {
        Notification notification = Notification.create(
                recipient, type, auctionId, resourceId, title, body, businessEventKey, LocalDateTime.now(clock)
        );
        return notificationRepository.save(notification);
    }

    private String title(NotificationType type) {
        return switch (type) {
            case AUCTION_WON -> "낙찰되었습니다";
            case BACKUP_OFFER_CREATED -> "차순위 구매 제안이 도착했습니다";
            case PAYMENT_EXPIRED -> "결제 기한이 만료되었습니다";
            // 아래 둘은 항상 recordPurchaseAgentEngaged/recordPurchaseAgentLost를 통해서만
            // 만들어진다(businessEventKey에 auctionId가 필요해서다) - 이 switch는 record()가
            // 컴파일되려면 모든 NotificationType을 다뤄야 해서 존재하지만 이 경로로는 호출되지 않는다.
            case PURCHASE_AGENT_ENGAGED -> "구매 대행이 입찰을 시작했습니다";
            case PURCHASE_AGENT_LOST -> "구매 대행이 낙찰하지 못했습니다";
        };
    }

    private String body(NotificationType type) {
        return switch (type) {
            case AUCTION_WON -> "낙찰되었습니다. 결제를 진행해주세요.";
            case BACKUP_OFFER_CREATED -> "차순위 구매 제안이 도착했습니다. 24시간 이내에 응답해주세요.";
            case PAYMENT_EXPIRED -> "결제 기한이 만료되어 차순위에게 구매 기회가 넘어갑니다.";
            case PURCHASE_AGENT_ENGAGED -> "설정하신 조건에 맞는 경매를 찾아 자동입찰을 등록했습니다.";
            case PURCHASE_AGENT_LOST -> "이번 경매는 낙찰하지 못했습니다. 더 이상 참여할 수 있는 경매가 없어 구매 대행을 종료합니다.";
        };
    }
}
