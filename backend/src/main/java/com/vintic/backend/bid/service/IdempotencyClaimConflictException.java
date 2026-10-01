package com.vintic.backend.bid.service;

// 동일 identity로 claim insert가 UNIQUE 제약과 충돌했다는 내부 제어 신호다.
// HTTP 응답으로 노출되지 않으며, 커맨드를 오케스트레이션하는 서비스(ManualBidService,
// AutoBidService 등)가 잡아서 별도 트랜잭션의 resolveAfterConflict()로 전환하는 데만 쓰인다.
// public인 이유: IdempotencyClaimService(bid.service)를 다른 패키지(autobid.service)의
// 오케스트레이션 서비스가 재사용하므로 그 호출부에서도 이 예외를 catch할 수 있어야 한다.
public class IdempotencyClaimConflictException extends RuntimeException {
    public IdempotencyClaimConflictException(Throwable cause) {
        super(cause);
    }
}
