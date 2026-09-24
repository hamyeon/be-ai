package com.vintic.backend.purchasegoal.service;

import com.vintic.backend.autobid.dto.AutoBidRegisterResponse;
import com.vintic.backend.autobid.service.AutoBidCommandService;
import com.vintic.backend.notification.service.NotificationRecorder;
import com.vintic.backend.purchasegoal.repository.PurchaseGoalRepository;
import com.vintic.backend.user.domain.User;
import com.vintic.backend.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

// Day 5의 유일한 물리 트랜잭션 경계다. Goal ACTIVE->ENGAGED 조건부 전이와 AutoBid 생성이 반드시
// 같은 트랜잭션에서 함께 성공하거나 함께 롤백돼야 하므로, 이 메서드 안에서는
// AutoBidCommandService.createAutoBid()가 던지는 예외를 절대 잡지 않는다 - 여기서 잡아 "정상
// 반환"으로 바꾸면 Spring이 롤백 대상 예외를 못 보고 Goal 전이만 커밋된 채 AutoBid는 없는
// 상태가 남을 수 있다. 실패를 "참여하지 않음" 결과로 바꾸는 일은 이 메서드 바깥
// (PurchaseGoalEngagementService, 트랜잭션 밖)에서 한다. AutoBid 쪽에 별도 트랜잭션을 새로
// 열지 않는다 - createAutoBid()는 REQUIRED이므로 이 메서드가 이미 연 트랜잭션에 그대로 합류한다.
//
// 락 순서: 이 goal row에 대한 조건부 UPDATE(그 자체로 배타적 row 락을 잡음) -> (성공 시에만)
// AutoBidCommandService.createAutoBid() 내부의 Auction FOR UPDATE -> AutoBidSetting FOR
// UPDATE(#45 순서 그대로 재사용). 다른 어떤 경로도 Auction/AutoBidSetting을 먼저 잠근 뒤 이
// goal row를 잠그지 않는다(기존 AutoBid 경로는 purchase_goals를 아예 건드리지 않는다) - 데드락
// 경로가 새로 생기지 않는다는 것을 PurchaseGoalEngagementConcurrencyMySqlIT로 실측 확인했다.
@Service
public class PurchaseGoalEngagementTransactionService {

    private final PurchaseGoalRepository purchaseGoalRepository;
    private final AutoBidCommandService autoBidCommandService;
    private final UserRepository userRepository;
    private final NotificationRecorder notificationRecorder;
    private final Clock clock;

    public PurchaseGoalEngagementTransactionService(
            PurchaseGoalRepository purchaseGoalRepository,
            AutoBidCommandService autoBidCommandService,
            UserRepository userRepository,
            NotificationRecorder notificationRecorder,
            Clock clock
    ) {
        this.purchaseGoalRepository = purchaseGoalRepository;
        this.autoBidCommandService = autoBidCommandService;
        this.userRepository = userRepository;
        this.notificationRecorder = notificationRecorder;
        this.clock = clock;
    }

    @Transactional
    public PurchaseGoalEngagementResult engage(Long goalId, Long auctionId, Long userId, long cap) {
        LocalDateTime now = LocalDateTime.now(clock);
        int updated = purchaseGoalRepository.transitionToEngaged(goalId, auctionId, now);
        if (updated != 1) {
            return PurchaseGoalEngagementResult.notEngaged("Goal 상태가 이미 바뀌어 참여하지 않음");
        }

        // createAutoBid()가 예외를 던지면 여기까지 오지 않는다 - Goal 조건부 UPDATE를 포함해
        // 이 트랜잭션 전체가 롤백되므로 PURCHASE_AGENT_ENGAGED 알림도 (아직 호출되지 않았으니)
        // 자연히 만들어지지 않는다. 등록이 성공했을 때만, 같은 트랜잭션에서 함께 커밋한다 -
        // Day 6 관찰이 나중에 같은 (goal, auction)을 다시 봐도 transitionToEngaged가 이미
        // affected=1이었던 이 시도 자체가 재실행되지 않으므로 중복 알림 경로가 없다.
        AutoBidRegisterResponse response = autoBidCommandService.createAutoBid(auctionId, userId, cap, null, goalId);
        User user = userRepository.getReferenceById(userId);
        notificationRecorder.recordPurchaseAgentEngaged(user, goalId, auctionId);

        return PurchaseGoalEngagementResult.engaged(auctionId, response.autoBidSettingId());
    }
}
