package com.vintic.backend.bid.service;

// 동일 identity로 claim insert가 UNIQUE 제약과 충돌했다는 내부 제어 신호다.
// HTTP 응답으로 노출되지 않으며, ManualBidService가 잡아서 별도 트랜잭션의
// resolveAfterConflict()로 전환하는 데만 쓰인다.
class IdempotencyClaimConflictException extends RuntimeException {
    IdempotencyClaimConflictException(Throwable cause) {
        super(cause);
    }
}
