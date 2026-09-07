package com.vintic.backend.analyze.job;

// 동일 (userId, idempotencyKey)로 claim insert가 UNIQUE 제약과 충돌했다는 내부 제어 신호다.
// ProductAnalysisJobService가 잡아서 별도 트랜잭션의 resolveAfterConflict()로 전환하는 데만 쓰인다.
public class ProductAnalysisJobClaimConflictException extends RuntimeException {
    public ProductAnalysisJobClaimConflictException(Throwable cause) {
        super(cause);
    }
}
