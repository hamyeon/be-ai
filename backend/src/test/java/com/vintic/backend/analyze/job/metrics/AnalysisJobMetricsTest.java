package com.vintic.backend.analyze.job.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

// AnalysisJobMetrics가 고정된 outcome/event enum 값만 tag로 쓰는지, processor.failures가
// outcome dimension 없이 success가 아닌 결과마다 정확히 1씩 증가하는지, queue.wait이
// 밀리초로 기록되는지 확인한다.
class AnalysisJobMetricsTest {

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final AnalysisJobMetrics metrics = new AnalysisJobMetrics(registry);

    @Test
    void success는_calls_latency만_기록하고_failures는_증가시키지_않는다() {
        Timer.Sample sample = metrics.startProcessorTimer();
        metrics.recordProcessorResult(sample, AnalysisJobMetrics.ProcessorOutcome.SUCCESS);

        assertThat(registry.counter("autique.analysis.processor.calls", "outcome", "success").count())
                .isEqualTo(1.0);
        assertThat(registry.find("autique.analysis.processor.failures").counter()).isNull();
        assertThat(registry.timer("autique.analysis.processor.latency", "outcome", "success").count())
                .isEqualTo(1L);
    }

    @Test
    void success가_아닌_모든_outcome은_failures를_outcome_dimension_없이_정확히_1_증가시킨다() {
        for (AnalysisJobMetrics.ProcessorOutcome outcome : List.of(
                AnalysisJobMetrics.ProcessorOutcome.TRANSIENT_FAILURE,
                AnalysisJobMetrics.ProcessorOutcome.PERMANENT_FAILURE,
                AnalysisJobMetrics.ProcessorOutcome.TIMEOUT,
                AnalysisJobMetrics.ProcessorOutcome.UNEXPECTED_FAILURE)) {
            Timer.Sample sample = metrics.startProcessorTimer();
            metrics.recordProcessorResult(sample, outcome);
        }

        assertThat(registry.counter("autique.analysis.processor.failures").count()).isEqualTo(4.0);
        assertThat(registry.get("autique.analysis.processor.failures").counter().getId().getTags()).isEmpty();
    }

    @Test
    void processor_calls_latency는_5개_outcome_값만_tag로_가진다() {
        for (AnalysisJobMetrics.ProcessorOutcome outcome : AnalysisJobMetrics.ProcessorOutcome.values()) {
            Timer.Sample sample = metrics.startProcessorTimer();
            metrics.recordProcessorResult(sample, outcome);
        }

        List<String> outcomeTagValues = registry.getMeters().stream()
                .filter(meter -> meter.getId().getName().equals("autique.analysis.processor.calls"))
                .map(meter -> meter.getId().getTag("outcome"))
                .collect(Collectors.toList());

        assertThat(outcomeTagValues).containsExactlyInAnyOrder(
                "success", "transient_failure", "permanent_failure", "timeout", "unexpected_failure");
    }

    @Test
    void queue_wait는_밀리초로_기록된다() {
        metrics.recordQueueWait(750);

        assertThat(registry.timer("autique.analysis.queue.wait").totalTime(TimeUnit.MILLISECONDS))
                .isEqualTo(750.0);
    }

    @Test
    void worker_events는_3개_event_값만_tag로_가진다() {
        metrics.recordWorkerEvent(AnalysisJobMetrics.WorkerEvent.RETRY);
        metrics.recordWorkerEvent(AnalysisJobMetrics.WorkerEvent.LEASE_LOST);
        metrics.recordWorkerEvent(AnalysisJobMetrics.WorkerEvent.UNEXPECTED);

        List<String> eventTagValues = registry.getMeters().stream()
                .filter(meter -> meter.getId().getName().equals("autique.analysis.worker.events"))
                .map(meter -> meter.getId().getTag("event"))
                .collect(Collectors.toList());

        assertThat(eventTagValues).containsExactlyInAnyOrder("retry", "lease_lost", "unexpected");
        assertThat(registry.counter("autique.analysis.worker.events", "event", "retry").count()).isEqualTo(1.0);
    }
}
