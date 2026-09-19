package com.vintic.backend.analyze.job.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

// Day10 관측성: SQS 기반 분석 파이프라인(Worker)의 제한된 meter만 고정 cardinality로 기록한다.
// ProcessorOutcome/WorkerEvent를 enum으로 고정해 SqsAnalysisJobHandler가 동적 문자열(예외
// 메시지·클래스명)을 그대로 태그 값으로 넘길 수 없게 한다 - CloudWatchMetricsConfig의 비용
// allowlist가 이 5개 meter만 내보내는 것을 전제로 하므로, 여기서 cardinality가 코드 수준에서
// 고정되지 않으면 그 전제가 깨진다.
//
// Worker 전용 profile로 제한한다 - API 프로세스는 이 meter를 만들지 않는다(만들면 API에서
// 항상 0인 Worker 시계열이 CloudWatch에 쌓인다).
@Component
@Profile("experiment-worker")
public class AnalysisJobMetrics {

    public enum ProcessorOutcome {
        SUCCESS("success"),
        TRANSIENT_FAILURE("transient_failure"),
        PERMANENT_FAILURE("permanent_failure"),
        TIMEOUT("timeout"),
        UNEXPECTED_FAILURE("unexpected_failure");

        private final String tagValue;

        ProcessorOutcome(String tagValue) {
            this.tagValue = tagValue;
        }

        public String tagValue() {
            return tagValue;
        }
    }

    public enum WorkerEvent {
        RETRY("retry"),
        LEASE_LOST("lease_lost"),
        UNEXPECTED("unexpected");

        private final String tagValue;

        WorkerEvent(String tagValue) {
            this.tagValue = tagValue;
        }

        public String tagValue() {
            return tagValue;
        }
    }

    static final String PROCESSOR_CALLS = "autique.analysis.processor.calls";
    static final String PROCESSOR_FAILURES = "autique.analysis.processor.failures";
    static final String PROCESSOR_LATENCY = "autique.analysis.processor.latency";
    static final String QUEUE_WAIT = "autique.analysis.queue.wait";
    static final String WORKER_EVENTS = "autique.analysis.worker.events";

    private final MeterRegistry meterRegistry;

    public AnalysisJobMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public Timer.Sample startProcessorTimer() {
        return Timer.start(meterRegistry);
    }

    // outcome이 SUCCESS가 아니면 processor.failures를 outcome dimension 없이 정확히 1
    // 증가시킨다 - CloudWatch Alarm이 outcome을 가리지 않고 "전체 실패율"만 단일 집계 지표로
    // 보게 하기 위함이다(비용 allowlist에 outcome별 실패 meter를 따로 두지 않는다).
    public void recordProcessorResult(Timer.Sample sample, ProcessorOutcome outcome) {
        meterRegistry.counter(PROCESSOR_CALLS, "outcome", outcome.tagValue()).increment();
        sample.stop(Timer.builder(PROCESSOR_LATENCY)
                .tag("outcome", outcome.tagValue())
                .register(meterRegistry));
        if (outcome != ProcessorOutcome.SUCCESS) {
            meterRegistry.counter(PROCESSOR_FAILURES).increment();
        }
    }

    public void recordQueueWait(long queueWaitMs) {
        Timer.builder(QUEUE_WAIT)
                .register(meterRegistry)
                .record(queueWaitMs, TimeUnit.MILLISECONDS);
    }

    public void recordWorkerEvent(WorkerEvent event) {
        meterRegistry.counter(WORKER_EVENTS, "event", event.tagValue()).increment();
    }
}
