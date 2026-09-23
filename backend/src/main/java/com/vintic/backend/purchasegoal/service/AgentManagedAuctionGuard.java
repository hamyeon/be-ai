package com.vintic.backend.purchasegoal.service;

import com.vintic.backend.common.exception.AgentManagedAuctionException;
import com.vintic.backend.purchasegoal.domain.PurchaseGoalStatus;
import com.vintic.backend.purchasegoal.repository.PurchaseGoalRepository;
import org.springframework.stereotype.Service;

// Day2 B: AutoBidSetting.purchaseGoalId가 가리키는 Goal이 "지금 이 경매를 실제로 관리 중"일
// 때만 사용자 본인의 수정/취소/수동 입찰을 막는다. 판단 기준은 매번 다시 계산한다 - purchaseGoalId
// 존재 자체(과거 연결값)가 아니라 연결된 Goal의 현재 status/currentAuctionId를 본다. 그래야
// Goal이 종료(FULFILLED/CANCELLED/EXPIRED)되거나 다른 경매로 옮겨간 뒤에는 과거 연결값만으로
// 막지 않는다는 요구(#Day2-B)를 그대로 만족한다.
//
// 이 조회는 non-locking read다 - Auction/AutoBidSetting FOR UPDATE 락 순서(#45)에 PurchaseGoal을
// 새로 끼워 넣지 않기 위한 의도적 단순화다. 오늘 시점엔 PurchaseGoal.status를 동시에 바꾸는
// 유일한 경로가 PurchaseGoalCommandService.cancelGoal() 하나뿐이라(Day 5 engage 로직 미구현)
// 실제 경합 창구가 없다 - Day 5가 동시성 있는 전이를 추가하면 이 가정을 다시 검토해야 한다.
@Service
public class AgentManagedAuctionGuard {

    private final PurchaseGoalRepository purchaseGoalRepository;

    public AgentManagedAuctionGuard(PurchaseGoalRepository purchaseGoalRepository) {
        this.purchaseGoalRepository = purchaseGoalRepository;
    }

    public boolean isManaged(Long purchaseGoalId, Long auctionId, Long userId) {
        if (purchaseGoalId == null) {
            return false;
        }
        return purchaseGoalRepository.findById(purchaseGoalId)
                .filter(goal -> goal.getUser().getId().equals(userId))
                .filter(goal -> goal.getStatus() == PurchaseGoalStatus.ENGAGED
                        || goal.getStatus() == PurchaseGoalStatus.CANCEL_REQUESTED)
                .filter(goal -> auctionId.equals(goal.getCurrentAuctionId()))
                .isPresent();
    }

    public void checkNotAgentManaged(Long purchaseGoalId, Long auctionId, Long userId) {
        if (isManaged(purchaseGoalId, auctionId, userId)) {
            throw new AgentManagedAuctionException(
                    "Purchase Agent가 관리 중인 경매입니다. auctionId: " + auctionId
            );
        }
    }
}
