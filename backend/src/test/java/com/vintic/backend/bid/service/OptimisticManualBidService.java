package com.vintic.backend.bid.service;

/**
 * #74-2 실험 전용(experiment/#74-optimistic-lock-retry): production {@link ManualBidService}와
 * 동일한 "얇은 진입점 + {@link IdempotencyClaimService} 위임" 구조를 그대로 재사용한다 -
 * ad-hoc 두 번째 idempotency 시스템을 만들지 않는다(§2). 유일한 차이는 command가
 * {@code bidCommandService.placeManualBid(...)} 대신 {@link OptimisticBidRetryOrchestrator#execute}
 * 를 호출한다는 것뿐이다.
 *
 * operationScope에 별도 접두("PLACE_BID_OPTIMISTIC:")를 써서 production PLACE_BID 흐름의
 * Idempotency row와 절대 겹치지 않게 한다(같은 유저가 같은 auction에 production 경로와
 * 이 실험 경로를 동시에 쓰는 시나리오는 없지만, 방어적으로 분리했다).
 *
 * claim(및 최종 response snapshot)은 이 메서드가 호출하는 claimAndExecute()의 단일 물리
 * 트랜잭션(T0)에서 커밋된다. 그 안에서 실행되는 {@code orchestrator.execute(...)}의 각
 * attempt는 {@code @Transactional(REQUIRES_NEW)}라 T0를 suspend한 채 독립적으로
 * 커밋/롤백된다 - "HTTP duplicate request"는 기존 claim UNIQUE 제약(#2 그대로)이,
 * "한 HTTP request 내부 optimistic conflict"는 orchestrator의 bounded retry가 담당하고
 * 서로 겹치지 않는다(완료보고 §Idempotency/Retry 관계 참고).
 */
public class OptimisticManualBidService {

    private final IdempotencyClaimService idempotencyClaimService;
    private final OptimisticBidRetryOrchestrator orchestrator;

    public OptimisticManualBidService(
            IdempotencyClaimService idempotencyClaimService, OptimisticBidRetryOrchestrator orchestrator
    ) {
        this.idempotencyClaimService = idempotencyClaimService;
        this.orchestrator = orchestrator;
    }

    public OptimisticBidOutcome placeBid(Long auctionId, Long userId, Long amount, String idempotencyKey) {
        String operationScope = "PLACE_BID_OPTIMISTIC:" + auctionId;
        String requestHash = BidRequestHash.sha256(amount);

        try {
            return idempotencyClaimService.claimAndExecute(
                    userId, operationScope, idempotencyKey, requestHash,
                    OptimisticBidOutcome.class,
                    claimId -> orchestrator.execute(auctionId, userId, amount, claimId)
            );
        } catch (IdempotencyClaimConflictException e) {
            return idempotencyClaimService.resolveAfterConflict(
                    userId, operationScope, idempotencyKey, requestHash, OptimisticBidOutcome.class
            );
        }
    }
}
