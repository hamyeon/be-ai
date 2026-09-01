package com.vintic.backend.bid.service;

import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * #74 실험 전용(experiment/#74-optimistic-lock-retry): non-transactional orchestrator다.
 * 트랜잭션 경계는 전적으로 {@link ManualBidAttemptExecutor}(실제 구현은
 * {@link OptimisticBidAttemptService}, REQUIRES_NEW)가 갖는다 - 이 클래스에는 의도적으로
 * {@code @Transactional}을 붙이지 않는다.
 *
 * 재시도 대상은 {@code OptimisticLockConflictExceptionSpikeIT}(#74-1 spike)로 실제 확인한
 * {@link ObjectOptimisticLockingFailureException} 한 타입뿐이다 - 그 상위 타입인
 * {@code DataAccessException}/{@code OptimisticLockingFailureException}까지 넓혀 잡지
 * 않는다(broad catch 금지). business exception과 CannotAcquireLockException 등 unrelated
 * DB 예외는 여기서 잡지 않고 그대로 전파되므로, 별도 분기 없이 구조적으로 "그 종류만" 재시도된다.
 *
 * 매 attempt는 {@code attemptExecutor.attempt(...)}를 원본 요청 파라미터(auctionId/userId/
 * amount/idempotencyId) 그대로 다시 호출한다 - 이전 실패 attempt의 Auction/Bid entity나 계산된
 * validation 결과를 이 orchestrator가 들고 있다가 재사용하는 코드 경로 자체가 없다.
 */
public class OptimisticBidRetryOrchestrator {

    // #74 실험 시작 전 고정(§C) - 본실험(#74-3/#74-4) 결과를 본 뒤에도 값을 바꾸지 않는다.
    // initial attempt 1회 + retry 4회 = 최대 5회 시도. backoff 없음(즉시 재시도).
    public static final int MAX_ATTEMPTS = 5;

    private final ManualBidAttemptExecutor attemptExecutor;

    public OptimisticBidRetryOrchestrator(ManualBidAttemptExecutor attemptExecutor) {
        this.attemptExecutor = attemptExecutor;
    }

    public OptimisticBidOutcome execute(Long auctionId, Long userId, Long amount, Long idempotencyId) {
        int conflictCount = 0;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                var response = attemptExecutor.attempt(auctionId, userId, amount, idempotencyId);
                return new OptimisticBidOutcome(response, attempt, conflictCount, false);
            } catch (ObjectOptimisticLockingFailureException conflict) {
                conflictCount++;
                if (attempt == MAX_ATTEMPTS) {
                    throw new OptimisticRetryExhaustedException(attempt, conflictCount, conflict);
                }
                // no backoff - §C 고정 정책.
            }
        }
        throw new IllegalStateException("unreachable: MAX_ATTEMPTS 루프가 반환/예외 없이 종료됨");
    }
}
