package com.vintic.backend.concurrency;

import com.vintic.backend.bid.dto.PlaceBidResponse;
import com.vintic.backend.bid.service.ManualBidAttemptExecutor;
import com.vintic.backend.concurrency.OptimisticConcurrencyPerformanceBenchmarkIT.AttemptInstrumentation;
import com.vintic.backend.concurrency.OptimisticConcurrencyPerformanceBenchmarkIT.InstrumentedAttemptExecutor;
import com.vintic.backend.concurrency.OptimisticConcurrencyPerformanceBenchmarkIT.RequestRecord;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import static com.vintic.backend.concurrency.OptimisticConcurrencyPerformanceBenchmarkIT.classifyOutcome;
import static com.vintic.backend.concurrency.OptimisticConcurrencyPerformanceBenchmarkIT.formatCsvRow;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * #74-4A(§7): 실제 MySQL/Spring 없이 harness의 순수 로직(outcome 분류, CSV row 포맷,
 * attempt/conflict instrumentation)만 검증한다. 본실험({@code OptimisticConcurrencyPerformanceBenchmarkIT})
 * 자체는 이 단계에서 실행하지 않는다.
 */
class OptimisticConcurrencyPerformanceBenchmarkHarnessTest {

    @Test
    void classifyOutcome_null이면_SUCCESS() {
        assertThat(classifyOutcome(null)).isEqualTo("SUCCESS");
    }

    @Test
    void classifyOutcome_business_rejection_예외는_BUSINESS_REJECTION() {
        assertThat(classifyOutcome("BidAmountTooLowException")).isEqualTo("BUSINESS_REJECTION");
        assertThat(classifyOutcome("AuctionClosedException")).isEqualTo("BUSINESS_REJECTION");
        assertThat(classifyOutcome("SellerCannotBidException")).isEqualTo("BUSINESS_REJECTION");
        assertThat(classifyOutcome("AlreadyHighestBidderException")).isEqualTo("BUSINESS_REJECTION");
        assertThat(classifyOutcome("AuctionNotStartedException")).isEqualTo("BUSINESS_REJECTION");
        assertThat(classifyOutcome("PenaltyRestrictedException")).isEqualTo("BUSINESS_REJECTION");
    }

    @Test
    void classifyOutcome_OptimisticRetryExhaustedException은_OPTIMISTIC_RETRY_EXHAUSTED() {
        assertThat(classifyOutcome("OptimisticRetryExhaustedException")).isEqualTo("OPTIMISTIC_RETRY_EXHAUSTED");
    }

    // §3 핵심 요구사항: AutoBidSetting FOR UPDATE/InnoDB lock 실패를 optimistic conflict로
    // 오분류하지 않는다 - CannotAcquireLockException은 UNEXPECTED_DB_FAILURE여야 한다.
    @Test
    void classifyOutcome_CannotAcquireLockException은_optimistic_conflict가_아니라_UNEXPECTED_DB_FAILURE() {
        assertThat(classifyOutcome("CannotAcquireLockException")).isEqualTo("UNEXPECTED_DB_FAILURE");
        assertThat(classifyOutcome("CannotAcquireLockException")).isNotEqualTo("OPTIMISTIC_RETRY_EXHAUSTED");
    }

    @Test
    void classifyOutcome_알수없는_예외도_UNEXPECTED_DB_FAILURE() {
        assertThat(classifyOutcome("DeadlockLoserDataAccessException")).isEqualTo("UNEXPECTED_DB_FAILURE");
    }

    @Test
    void formatCsvRow는_필드_순서와_소수점_포맷을_고정한다() {
        RequestRecord record = new RequestRecord(
                3, 5, 8, 12.3456, "SUCCESS", "", 2, 1, 1, false, 987.654321
        );

        String row = formatCsvRow(record);

        assertThat(row).isEqualTo("3,5,8,12.346,SUCCESS,,2,1,1,false,987.654");
    }

    @Test
    void formatCsvRow는_retryCount가_attemptsUsed_1과_일치할_때만_의미가_맞다() {
        // 이 테스트는 retryCount가 harness에서 항상 attemptsUsed-1로 계산됨을 문서화한다
        // (§2 고정 정의) - formatCsvRow 자체는 그 계산을 재검증하지 않고 그대로 출력만 한다.
        RequestRecord record = new RequestRecord(
                1, 0, 8, 1.0, "OPTIMISTIC_RETRY_EXHAUSTED", "OptimisticRetryExhaustedException",
                5, 4, 5, true, 10.0
        );
        assertThat(record.attemptsUsed() - 1).isEqualTo(record.retryCount());
        assertThat(formatCsvRow(record)).contains(",5,4,5,true,");
    }

    // ---- AttemptInstrumentation / InstrumentedAttemptExecutor ----

    @Test
    void InstrumentedAttemptExecutor는_성공_시_attempt_1회만_기록하고_conflict는_0이다() {
        AttemptInstrumentation instrumentation = new AttemptInstrumentation();
        ManualBidAttemptExecutor delegate = mock(ManualBidAttemptExecutor.class);
        PlaceBidResponse response = new PlaceBidResponse(1L, 15000L, 15000L, 20000L, "홍*동", true, false, false, null, 0);
        when(delegate.attempt(1L, 2L, 15000L, 99L)).thenReturn(response);
        InstrumentedAttemptExecutor executor = new InstrumentedAttemptExecutor(delegate, instrumentation);

        instrumentation.reset();
        PlaceBidResponse actual = executor.attempt(1L, 2L, 15000L, 99L);

        assertThat(actual).isEqualTo(response);
        assertThat(instrumentation.attempts()).isEqualTo(1);
        assertThat(instrumentation.conflicts()).isEqualTo(0);
    }

    @Test
    void InstrumentedAttemptExecutor는_ObjectOptimisticLockingFailureException만_conflict로_집계하고_그대로_다시_던진다() {
        AttemptInstrumentation instrumentation = new AttemptInstrumentation();
        ManualBidAttemptExecutor delegate = mock(ManualBidAttemptExecutor.class);
        ObjectOptimisticLockingFailureException conflict =
                new ObjectOptimisticLockingFailureException("Auction", 1L);
        when(delegate.attempt(1L, 2L, 15000L, 99L)).thenThrow(conflict);
        InstrumentedAttemptExecutor executor = new InstrumentedAttemptExecutor(delegate, instrumentation);

        instrumentation.reset();
        assertThatThrownBy(() -> executor.attempt(1L, 2L, 15000L, 99L)).isSameAs(conflict);

        assertThat(instrumentation.attempts()).isEqualTo(1);
        assertThat(instrumentation.conflicts()).isEqualTo(1);
        verify(delegate, times(1)).attempt(1L, 2L, 15000L, 99L);
    }

    @Test
    void InstrumentedAttemptExecutor는_unrelated_예외를_conflict로_집계하지_않는다() {
        AttemptInstrumentation instrumentation = new AttemptInstrumentation();
        ManualBidAttemptExecutor delegate = mock(ManualBidAttemptExecutor.class);
        RuntimeException unrelated = new org.springframework.dao.CannotAcquireLockException("lock timeout");
        when(delegate.attempt(1L, 2L, 15000L, 99L)).thenThrow(unrelated);
        InstrumentedAttemptExecutor executor = new InstrumentedAttemptExecutor(delegate, instrumentation);

        instrumentation.reset();
        assertThatThrownBy(() -> executor.attempt(1L, 2L, 15000L, 99L)).isSameAs(unrelated);

        assertThat(instrumentation.attempts()).isEqualTo(1);
        assertThat(instrumentation.conflicts()).isEqualTo(0);
    }

    @Test
    void reset은_스레드별_attempt_conflict_카운트를_0으로_되돌린다() {
        AttemptInstrumentation instrumentation = new AttemptInstrumentation();
        instrumentation.recordAttempt();
        instrumentation.recordAttempt();
        instrumentation.recordConflict();
        assertThat(instrumentation.attempts()).isEqualTo(2);
        assertThat(instrumentation.conflicts()).isEqualTo(1);

        instrumentation.reset();

        assertThat(instrumentation.attempts()).isEqualTo(0);
        assertThat(instrumentation.conflicts()).isEqualTo(0);
    }
}
