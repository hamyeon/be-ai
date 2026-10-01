package com.vintic.backend.bid.service;

import com.vintic.backend.bid.dto.PlaceBidResponse;
import com.vintic.backend.common.exception.BidAmountTooLowException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * #74-1 core unit test(§8) - 실제 MySQL/Spring 트랜잭션 없이 순수하게 retry 판단 로직만
 * 검증한다. {@link ManualBidAttemptExecutor}를 Mockito로 대체해 "매 attempt가 원본 요청
 * 그대로 다시 호출되는가", "무엇을 retry하고 무엇을 즉시 전파하는가", "몇 번째에 exhaustion인가"만
 * 확인한다. 실제 MySQL version conflict/Idempotency/rollback 상호작용은 #74-2 범위다.
 */
class OptimisticBidRetryOrchestratorTest {

    private static final Long AUCTION_ID = 1L;
    private static final Long USER_ID = 2L;
    private static final Long AMOUNT = 15000L;
    private static final Long IDEMPOTENCY_ID = 99L;

    private final ManualBidAttemptExecutor attemptExecutor = mock(ManualBidAttemptExecutor.class);
    private final OptimisticBidRetryOrchestrator orchestrator = new OptimisticBidRetryOrchestrator(attemptExecutor);

    private PlaceBidResponse dummyResponse() {
        return new PlaceBidResponse(1L, AMOUNT, AMOUNT, AMOUNT + 5000, "홍*동", true, false, false, null, 0);
    }

    @Test
    void 첫_시도에_성공하면_재시도_없이_바로_반환한다() {
        PlaceBidResponse response = dummyResponse();
        when(attemptExecutor.attempt(AUCTION_ID, USER_ID, AMOUNT, IDEMPOTENCY_ID)).thenReturn(response);

        OptimisticBidOutcome outcome = orchestrator.execute(AUCTION_ID, USER_ID, AMOUNT, IDEMPOTENCY_ID);

        assertThat(outcome.response()).isEqualTo(response);
        assertThat(outcome.attemptsUsed()).isEqualTo(1);
        assertThat(outcome.conflictCount()).isEqualTo(0);
        assertThat(outcome.exhausted()).isFalse();
        verify(attemptExecutor, times(1)).attempt(AUCTION_ID, USER_ID, AMOUNT, IDEMPOTENCY_ID);
    }

    @Test
    void optimistic_conflict가_두번_발생한_뒤_세번째_시도에서_성공하면_카운트가_정확히_집계된다() {
        PlaceBidResponse response = dummyResponse();
        when(attemptExecutor.attempt(AUCTION_ID, USER_ID, AMOUNT, IDEMPOTENCY_ID))
                .thenThrow(new ObjectOptimisticLockingFailureException("Auction", AUCTION_ID))
                .thenThrow(new ObjectOptimisticLockingFailureException("Auction", AUCTION_ID))
                .thenReturn(response);

        OptimisticBidOutcome outcome = orchestrator.execute(AUCTION_ID, USER_ID, AMOUNT, IDEMPOTENCY_ID);

        assertThat(outcome.response()).isEqualTo(response);
        assertThat(outcome.attemptsUsed()).isEqualTo(3);
        assertThat(outcome.conflictCount()).isEqualTo(2);
        assertThat(outcome.exhausted()).isFalse();
        // 매 attempt가 "원본 요청 그대로" 재호출됐는지 - 이전 실패에서 파생된 값 없이 동일 인자.
        verify(attemptExecutor, times(3)).attempt(
                eq(AUCTION_ID), eq(USER_ID), eq(AMOUNT), eq(IDEMPOTENCY_ID)
        );
    }

    @Test
    void MAX_ATTEMPTS까지_전부_conflict면_exhaustion_예외를_던지고_그_이상_재시도하지_않는다() {
        when(attemptExecutor.attempt(AUCTION_ID, USER_ID, AMOUNT, IDEMPOTENCY_ID))
                .thenThrow(new ObjectOptimisticLockingFailureException("Auction", AUCTION_ID));

        assertThatThrownBy(() -> orchestrator.execute(AUCTION_ID, USER_ID, AMOUNT, IDEMPOTENCY_ID))
                .isInstanceOf(OptimisticRetryExhaustedException.class)
                .satisfies(e -> {
                    OptimisticRetryExhaustedException exhausted = (OptimisticRetryExhaustedException) e;
                    assertThat(exhausted.getAttemptsUsed()).isEqualTo(OptimisticBidRetryOrchestrator.MAX_ATTEMPTS);
                    assertThat(exhausted.getConflictCount()).isEqualTo(OptimisticBidRetryOrchestrator.MAX_ATTEMPTS);
                });

        // bounded retry 확인 - MAX_ATTEMPTS(5)회를 정확히 넘지 않는다(무한 retry 아님).
        verify(attemptExecutor, times(OptimisticBidRetryOrchestrator.MAX_ATTEMPTS))
                .attempt(AUCTION_ID, USER_ID, AMOUNT, IDEMPOTENCY_ID);
    }

    @Test
    void business_rejection은_재시도하지_않고_그대로_전파한다() {
        BidAmountTooLowException businessException = new BidAmountTooLowException("입찰 금액은 20000원 이상이어야 합니다.");
        when(attemptExecutor.attempt(AUCTION_ID, USER_ID, AMOUNT, IDEMPOTENCY_ID)).thenThrow(businessException);

        assertThatThrownBy(() -> orchestrator.execute(AUCTION_ID, USER_ID, AMOUNT, IDEMPOTENCY_ID))
                .isSameAs(businessException);

        verify(attemptExecutor, times(1)).attempt(AUCTION_ID, USER_ID, AMOUNT, IDEMPOTENCY_ID);
    }

    @Test
    void unrelated_DB_예외인_CannotAcquireLockException은_재시도하지_않고_그대로_전파한다() {
        CannotAcquireLockException dbException = new CannotAcquireLockException("lock timeout");
        when(attemptExecutor.attempt(AUCTION_ID, USER_ID, AMOUNT, IDEMPOTENCY_ID)).thenThrow(dbException);

        assertThatThrownBy(() -> orchestrator.execute(AUCTION_ID, USER_ID, AMOUNT, IDEMPOTENCY_ID))
                .isSameAs(dbException);

        verify(attemptExecutor, times(1)).attempt(AUCTION_ID, USER_ID, AMOUNT, IDEMPOTENCY_ID);
    }

    @Test
    void unrelated_DB_예외인_DataIntegrityViolationException은_재시도하지_않고_그대로_전파한다() {
        DataIntegrityViolationException dbException = new DataIntegrityViolationException("constraint violation");
        when(attemptExecutor.attempt(AUCTION_ID, USER_ID, AMOUNT, IDEMPOTENCY_ID)).thenThrow(dbException);

        assertThatThrownBy(() -> orchestrator.execute(AUCTION_ID, USER_ID, AMOUNT, IDEMPOTENCY_ID))
                .isSameAs(dbException);

        verify(attemptExecutor, times(1)).attempt(AUCTION_ID, USER_ID, AMOUNT, IDEMPOTENCY_ID);
    }
}
