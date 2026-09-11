package com.vintic.backend.analyze.job.worker;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// SqsAnalysisJobPollerLifecycle이 Spring 생명주기(@PostConstruct/@PreDestroy)에 poller의
// start/stop/awaitTermination을 올바른 순서로, 설정된 timeout으로 연결하는지 확인한다.
class SqsAnalysisJobPollerLifecycleTest {

    @Test
    void PostConstruct는_start를_호출한다() {
        SqsAnalysisJobPoller poller = mock(SqsAnalysisJobPoller.class);
        SqsAnalysisJobPollerLifecycle lifecycle = new SqsAnalysisJobPollerLifecycle(poller, 75_000L);

        lifecycle.startPolling();

        verify(poller).start();
    }

    @Test
    void PreDestroy는_stop_이후_설정된_timeout으로_awaitTermination을_호출한다() {
        SqsAnalysisJobPoller poller = mock(SqsAnalysisJobPoller.class);
        when(poller.awaitTermination(Duration.ofMillis(12_345L))).thenReturn(true);
        SqsAnalysisJobPollerLifecycle lifecycle = new SqsAnalysisJobPollerLifecycle(poller, 12_345L);

        lifecycle.stopPolling();

        InOrder order = Mockito.inOrder(poller);
        order.verify(poller).stop();
        order.verify(poller).awaitTermination(eq(Duration.ofMillis(12_345L)));
    }

    @Test
    void awaitTermination이_false여도_예외_없이_끝난다() {
        SqsAnalysisJobPoller poller = mock(SqsAnalysisJobPoller.class);
        when(poller.awaitTermination(Mockito.any())).thenReturn(false);
        SqsAnalysisJobPollerLifecycle lifecycle = new SqsAnalysisJobPollerLifecycle(poller, 1_000L);

        lifecycle.stopPolling(); // 예외를 던지지 않아야 한다(경고 로그만 남김)

        verify(poller).stop();
        verify(poller).awaitTermination(Duration.ofMillis(1_000L));
    }
}
