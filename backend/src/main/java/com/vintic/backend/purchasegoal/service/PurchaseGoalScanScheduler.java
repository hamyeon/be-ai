package com.vintic.backend.purchasegoal.service;

import com.vintic.backend.purchasegoal.domain.PurchaseGoal;
import com.vintic.backend.purchasegoal.domain.PurchaseGoalStatus;
import com.vintic.backend.purchasegoal.repository.PurchaseGoalRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

// Day 6: Purchase Agent의 3단계 scan을 5분마다 순서대로 돈다(설계안). 순서가 중요하다 -
// 1) 만료를 먼저 처리해야 이미 지난 deadline의 ACTIVE goal이 3단계에서 새로 참여를 시도하지
//    않는다. 2) 결과 관찰을 먼저 끝내야 패배로 ACTIVE에 복귀한 goal이 같은 회차의 3) 탐색/참여
//    대상에 들어갈 수 있다("패배 후 재탐색"). 각 phase는 후보 id 조회(non-locking) 후 goal마다
//    개별 트랜잭션으로 처리한다(AuctionEndScheduler/BackupOfferExpirationScheduler와 동일한 구조) -
//    한 goal의 실패(AI 실패 포함)가 다른 goal 처리를 막지 않는다. AI 실패는 예외로만 올라오고
//    Goal 상태를 건드리는 코드 경로가 없으므로(Day 3/4는 조회·계산만 하고 Day 5는 성공한 경우에만
//    상태를 바꾼다) "AI 실패 = 패배/종료"로 기록되는 경로 자체가 없다.
//
// 두 서버가 같은 회차를 동시에 실행해도 별도 전역 락을 두지 않는다 - 최종 상태 전이는 항상
// PurchaseGoalRepository의 조건부 UPDATE(각 row의 배타적 락)가 결정하고, AutoBid 등록은
// PurchaseGoalEngagementTransactionService의 단일 트랜잭션이 결정한다 - 이 scheduler 자체는
// "후보를 누가 먼저 집어드는가"만 다투고, 그 다툼의 결과는 항상 DB가 한 쪽만 승인한다.
@Component
@Slf4j
public class PurchaseGoalScanScheduler {

    private final PurchaseGoalRepository purchaseGoalRepository;
    private final PurchaseGoalExpirationService expirationService;
    private final PurchaseGoalResultObservationService resultObservationService;
    private final PurchaseGoalCandidateFinder candidateFinder;
    private final PurchaseGoalCandidateRanker candidateRanker;
    private final PurchaseGoalEngagementService engagementService;
    private final Clock clock;
    private final boolean enabled;

    public PurchaseGoalScanScheduler(
            PurchaseGoalRepository purchaseGoalRepository,
            PurchaseGoalExpirationService expirationService,
            PurchaseGoalResultObservationService resultObservationService,
            PurchaseGoalCandidateFinder candidateFinder,
            PurchaseGoalCandidateRanker candidateRanker,
            PurchaseGoalEngagementService engagementService,
            Clock clock,
            @Value("${purchase-agent.scan.enabled:false}") boolean enabled
    ) {
        this.purchaseGoalRepository = purchaseGoalRepository;
        this.expirationService = expirationService;
        this.resultObservationService = resultObservationService;
        this.candidateFinder = candidateFinder;
        this.candidateRanker = candidateRanker;
        this.engagementService = engagementService;
        this.clock = clock;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${purchase-agent.scan.cron:0 */5 * * * *}")
    public void scan() {
        if (!enabled) {
            return;
        }
        expirePhase();
        observePhase();
        exploreAndEngagePhase();
    }

    private void expirePhase() {
        int expired = expirationService.expireDueGoals();
        if (expired > 0) {
            log.info("Purchase Agent Goal 만료 처리를 했습니다. count={}", expired);
        }
    }

    private void observePhase() {
        List<Long> candidateIds = purchaseGoalRepository.findEngagedOrCancelRequestedGoalIds();
        int failed = 0;
        for (Long goalId : candidateIds) {
            try {
                resultObservationService.observeIfDue(goalId);
            } catch (RuntimeException e) {
                failed++;
                log.warn("Purchase Agent 결과 관찰에 실패했습니다. goalId={}, message={}", goalId, e.getMessage());
            }
        }
        if (!candidateIds.isEmpty()) {
            log.info("Purchase Agent 결과 관찰을 시도했습니다. candidates={}, failed={}", candidateIds.size(), failed);
        }
    }

    private void exploreAndEngagePhase() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<Long> candidateIds = purchaseGoalRepository.findActiveGoalIdsForScan(now);
        int engaged = 0;
        int failed = 0;
        for (Long goalId : candidateIds) {
            try {
                PurchaseGoal goal = purchaseGoalRepository.findById(goalId).orElse(null);
                if (goal == null || goal.getStatus() != PurchaseGoalStatus.ACTIVE) {
                    // phase 2가 방금 EXPIRED로 만료시켰거나 다른 서버가 먼저 처리한 경우 - 건너뛴다.
                    continue;
                }
                List<PurchaseGoalCandidate> candidates = candidateFinder.findCandidates(goal);
                Optional<PurchaseGoalRankedCandidate> topCandidate = candidateRanker.rankTopCandidate(goal, candidates);
                PurchaseGoalEngagementResult result = engagementService.attemptEngage(goal, topCandidate);
                if (result.engaged()) {
                    engaged++;
                }
            } catch (RuntimeException e) {
                failed++;
                log.warn("Purchase Agent 탐색/참여 처리에 실패했습니다. goalId={}, message={}", goalId, e.getMessage());
            }
        }
        if (!candidateIds.isEmpty()) {
            log.info(
                    "Purchase Agent 탐색/참여를 시도했습니다. candidates={}, engaged={}, failed={}",
                    candidateIds.size(), engaged, failed
            );
        }
    }
}
