package com.vintic.backend.bid.service;

/**
 * #74 실험 전용: bounded retry(§C, {@link OptimisticBidRetryOrchestrator#MAX_ATTEMPTS} 고정)를
 * 모두 소진했음을 나타낸다. production 40909(CONCURRENT_CONFLICT, GlobalExceptionHandler의
 * PessimisticLockingFailureException 매핑)와 "다른 요청과 충돌해 재시도가 필요하다"는 동일한
 * 의미를 표현하되, 이 실험 경로는 production endpoint를 거치지 않으므로 GlobalExceptionHandler에
 * 새 매핑을 추가하지 않는다 - harness/보고 수준에서만 같은 의미로 취급한다.
 *
 * retry 후 최신 상태 기준 business validation 실패(예: BID_AMOUNT_TOO_LOW)는 이 예외가 아니라
 * 해당 business exception 그대로 전파된다 - exhaustion이 아니라 정상 거부다(§6).
 */
public class OptimisticRetryExhaustedException extends RuntimeException {

    private final int attemptsUsed;
    private final int conflictCount;

    public OptimisticRetryExhaustedException(int attemptsUsed, int conflictCount, Throwable lastConflict) {
        super("Optimistic retry exhausted after " + attemptsUsed + " attempts (" + conflictCount + " conflicts)",
                lastConflict);
        this.attemptsUsed = attemptsUsed;
        this.conflictCount = conflictCount;
    }

    public int getAttemptsUsed() {
        return attemptsUsed;
    }

    public int getConflictCount() {
        return conflictCount;
    }
}
