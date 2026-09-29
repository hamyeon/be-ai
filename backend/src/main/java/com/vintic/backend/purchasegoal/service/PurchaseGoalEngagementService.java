package com.vintic.backend.purchasegoal.service;

import com.vintic.backend.ai.purchase.price.PriceEstimate;
import com.vintic.backend.ai.purchase.price.PriceEstimateProvider;
import com.vintic.backend.ai.purchase.price.PriceEstimateQuery;
import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.purchasegoal.domain.PurchaseGoal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

// Purchase Agent Day 5: Day 4가 고른 최상위 후보를 실제로 참여(ENGAGED 전이 + AutoBid 등록)시킨다.
// Day 6이 3단계 Scheduler(Day3 findCandidates -> Day4 rankTopCandidate -> 여기 attemptEngage)를
// 연결할 때 부를 진입점이다.
//
// 등록 직전 서버 시세를 다시 확인하고 cap을 재계산한다 - Day 4가 넘긴 priceEstimate/cap 스냅샷을
// 그대로 쓰지 않는다(그 사이 시세가 바뀌었을 수 있다). 이 재확인은 트랜잭션 밖에서 수행한다 -
// PriceEstimateProvider 호출(캐시/외부 의존)을 기다리는 동안 DB 락을 잡지 않기 위해서다
// (PurchaseGoalCandidateRanker의 Matcher 호출과 동일한 원칙). 실제 물리 트랜잭션 경계는
// PurchaseGoalEngagementTransactionService 하나뿐이다 - 그 안에서 던진 예외(AutoBid 등록 실패)는
// 여기서 잡아 "참여하지 않음"으로 바꾼다. 이미 트랜잭션이 롤백된 뒤이므로 Goal ENGAGED 전이도
// 함께 되돌아가 있다.
@Service
@Slf4j
public class PurchaseGoalEngagementService {

    // Day 4와 동일한 초기값. Product.recommendedPrice(판매자 입력값)는 쓰지 않는다.
    private static final double CAP_RATIO = 1.0;

    private final PriceEstimateProvider priceEstimateProvider;
    private final PurchaseGoalEngagementTransactionService engagementTransactionService;

    public PurchaseGoalEngagementService(
            PriceEstimateProvider priceEstimateProvider,
            PurchaseGoalEngagementTransactionService engagementTransactionService
    ) {
        this.priceEstimateProvider = priceEstimateProvider;
        this.engagementTransactionService = engagementTransactionService;
    }

    // topCandidate가 비어 있으면(Day 4가 후보를 못 찾음) 아무 상태도 바꾸지 않는다.
    public PurchaseGoalEngagementResult attemptEngage(PurchaseGoal goal, Optional<PurchaseGoalRankedCandidate> topCandidate) {
        if (topCandidate.isEmpty()) {
            return PurchaseGoalEngagementResult.notEngaged("Day 4 후보 없음");
        }

        Auction auction = topCandidate.get().auction();

        Optional<PriceEstimate> freshEstimate;
        try {
            freshEstimate = priceEstimateProvider.estimate(PriceEstimateQuery.of(auction.getProduct()));
        } catch (RuntimeException e) {
            log.warn("Purchase Agent 참여 직전 시세 재확인 실패 - goalId: {}, auctionId: {}", goal.getId(), auction.getId(), e);
            return PurchaseGoalEngagementResult.notEngaged("시세 재확인 실패");
        }
        if (freshEstimate.isEmpty() || freshEstimate.get().estimatedPrice() <= 0) {
            return PurchaseGoalEngagementResult.notEngaged("서버 시세 없음");
        }

        long cap = Math.min(goal.getHardMaxAmount(), (long) Math.floor(freshEstimate.get().estimatedPrice() * CAP_RATIO));

        try {
            return engagementTransactionService.engage(goal.getId(), auction.getId(), goal.getUser().getId(), cap);
        } catch (RuntimeException e) {
            log.info("Purchase Agent 참여 실패 - goalId: {}, auctionId: {}, cause: {}", goal.getId(), auction.getId(), e.toString());
            return PurchaseGoalEngagementResult.notEngaged(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }
}
