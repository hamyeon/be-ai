package com.vintic.backend.bid.service;

import com.vintic.backend.bid.dto.PlaceBidResponse;

/**
 * #74 실험 전용(experiment/#74-optimistic-lock-retry): {@link OptimisticBidRetryOrchestrator}가
 * 의존하는 최소 인터페이스다. {@link OptimisticBidAttemptService}(REQUIRES_NEW 트랜잭션 bean)가
 * 실제 구현체이고, 순수 orchestrator 단위 테스트에서는 Mockito로 이 인터페이스만 대체한다 -
 * orchestrator 테스트가 Spring 트랜잭션 프록시에 의존하지 않게 하기 위함이다.
 */
public interface ManualBidAttemptExecutor {

    PlaceBidResponse attempt(Long auctionId, Long userId, Long amount, Long idempotencyId);
}
