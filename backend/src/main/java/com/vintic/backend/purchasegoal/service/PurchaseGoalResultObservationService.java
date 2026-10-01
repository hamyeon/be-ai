package com.vintic.backend.purchasegoal.service;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.domain.AuctionStatus;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.notification.service.NotificationRecorder;
import com.vintic.backend.order.repository.OrderRepository;
import com.vintic.backend.purchasegoal.domain.PurchaseGoal;
import com.vintic.backend.purchasegoal.domain.PurchaseGoalStatus;
import com.vintic.backend.purchasegoal.repository.PurchaseGoalRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

// Day 6 스캔 phase 2(결과 관찰). ENGAGED/CANCEL_REQUESTED Goal 한 건의 currentAuctionId를 보고
// 그 경매가 끝났는지, 끝났다면 이겼는지를 판정해 다음 상태로 옮긴다.
//
// 낙찰 판정은 Day 0에서 확인한 정산 트랜잭션의 결과를 그대로 쓴다 - AuctionEndService.endIfDue()가
// end()+settle()(Order 생성 포함, 낙찰자 없으면 Order 없음)을 하나의 물리 트랜잭션으로 commit하므로,
// 이 메서드가 non-locking으로 읽는 Auction.status==ENDED와 Order 존재 여부는 항상 같은 커밋
// 시점에 함께 보인다 - "ENDED인데 Order가 아직 안 보이는" 중간 상태를 걱정할 필요가 없다.
// Order.findByAuctionIdAndBuyerId(auctionId, buyerId) 존재 = 승리(§1).
//
// 실제 상태 반영은 PurchaseGoalRepository의 조건부 UPDATE 4종(resolveWin/resolveLossToActive/
// resolveLossToExpired/resolveCancelRequestedToCancelled)이 한다 - 전부 goalId + currentAuctionId
// (여기서 관찰한 그 경매) + 이전 상태를 WHERE에 걸어, 중복 관찰이나 늦게 도착한 관찰이 그 사이
// 이미 새로 참여한(currentAuctionId가 바뀐) goal을 잘못 덮어쓰지 않게 한다.
@Service
public class PurchaseGoalResultObservationService {

    private final PurchaseGoalRepository purchaseGoalRepository;
    private final AuctionRepository auctionRepository;
    private final OrderRepository orderRepository;
    private final NotificationRecorder notificationRecorder;
    private final Clock clock;

    public PurchaseGoalResultObservationService(
            PurchaseGoalRepository purchaseGoalRepository,
            AuctionRepository auctionRepository,
            OrderRepository orderRepository,
            NotificationRecorder notificationRecorder,
            Clock clock
    ) {
        this.purchaseGoalRepository = purchaseGoalRepository;
        this.auctionRepository = auctionRepository;
        this.orderRepository = orderRepository;
        this.notificationRecorder = notificationRecorder;
        this.clock = clock;
    }

    @Transactional
    public void observeIfDue(Long goalId) {
        PurchaseGoal goal = purchaseGoalRepository.findById(goalId).orElse(null);
        if (goal == null) {
            return;
        }
        PurchaseGoalStatus status = goal.getStatus();
        if (status != PurchaseGoalStatus.ENGAGED && status != PurchaseGoalStatus.CANCEL_REQUESTED) {
            // 이미 다른 서버/앞선 phase가 처리했다 - 다시 관찰할 필요가 없다.
            return;
        }
        Long auctionId = goal.getCurrentAuctionId();
        if (auctionId == null) {
            // 불변식상 ENGAGED/CANCEL_REQUESTED는 항상 currentAuctionId를 가진다 - 방어적으로만 둔다.
            return;
        }

        Auction auction = auctionRepository.findById(auctionId).orElse(null);
        if (auction == null) {
            return;
        }
        if (auction.getStatus() != AuctionStatus.ENDED && auction.getStatus() != AuctionStatus.CANCELED) {
            // 아직 진행 중(LIVE/SCHEDULED) - 기다린다.
            return;
        }

        LocalDateTime now = LocalDateTime.now(clock);
        boolean won = orderRepository.findByAuctionIdAndBuyerId(auctionId, goal.getUser().getId()).isPresent();

        if (won) {
            // #Day7: 새 알림을 만들지 않는다 - AuctionSettlementService.settle()이 Order를 만든
            // 시점에 이미 AUCTION_WON을 기록했다(승자가 Agent가 관리한 사용자여도 동일 경로).
            purchaseGoalRepository.resolveWin(goalId, auctionId, now);
            return;
        }
        // 아래 세 조건부 UPDATE 모두 영향받은 row가 1일 때만(=이 호출이 실제로 전이를 만들어낸
        // 경우에만) PURCHASE_AGENT_LOST를 기록한다 - 0이면 이미 다른 서버/이전 스캔이 처리했다는
        // 뜻이라 다시 알림을 만들면 중복이 된다. UPDATE와 알림 저장이 같은 트랜잭션이라 커밋도
        // 함께 된다.
        if (status == PurchaseGoalStatus.CANCEL_REQUESTED) {
            if (purchaseGoalRepository.resolveCancelRequestedToCancelled(goalId, auctionId, now) == 1) {
                notificationRecorder.recordPurchaseAgentLost(goal.getUser(), goalId, auctionId, false);
            }
            return;
        }
        if (goal.getDeadline().isAfter(now)) {
            if (purchaseGoalRepository.resolveLossToActive(goalId, auctionId, now) == 1) {
                notificationRecorder.recordPurchaseAgentLost(goal.getUser(), goalId, auctionId, true);
            }
        } else {
            if (purchaseGoalRepository.resolveLossToExpired(goalId, auctionId, now) == 1) {
                notificationRecorder.recordPurchaseAgentLost(goal.getUser(), goalId, auctionId, false);
            }
        }
    }
}
