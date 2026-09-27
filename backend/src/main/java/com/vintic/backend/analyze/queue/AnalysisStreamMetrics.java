package com.vintic.backend.analyze.queue;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

// AnalysisStreamRecoveryScheduler가 관측하는 지표. AiCallMetrics와 동일한 방식(Micrometer
// MeterRegistry, 신규 의존성 없음 - actuator에 이미 포함)으로 "지금 뭔가 이상한지"를 훑을 수
// 있는 숫자만 남긴다.
//
// 주의: 이 클래스는 지표를 "계측"만 한다. 실제로 어디로 스크레이핑되고 어떤 조건에서 알람이
// 울리는지는 이 프로젝트에 아직 Prometheus/CloudWatch 연동이 없어 여기서 보장하지 않는다 -
// docs/ai-async-analysis.md에 임계값 조건을 문서화해둔다("계측 완료"와 "알람 연결 완료"는
// 다르다).
@Component
public class AnalysisStreamMetrics {

    private final MeterRegistry meterRegistry;

    // XPENDING 조회 자체가 실패하면 이 값들은 갱신되지 않고 "마지막 성공값"으로 남는다 -
    // lastSuccessfulCheckEpochMs를 함께 봐야 그 값이 최신인지 판단할 수 있다.
    private final AtomicLong pendingCount = new AtomicLong(-1);
    private final AtomicLong oldestIdleMs = new AtomicLong(-1);
    private final AtomicLong lastSuccessfulCheckEpochMs = new AtomicLong(0);

    public AnalysisStreamMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;

        Gauge.builder("analysis.stream.pending.count", pendingCount, AtomicLong::get)
                .description("PEL(pending entries list)에 남은 작업 수. XPENDING 조회가 실패하면 마지막 성공값이 그대로 남는다")
                .register(meterRegistry);
        Gauge.builder("analysis.stream.pending.oldest_idle_ms", oldestIdleMs, AtomicLong::get)
                .description("가장 오래 대기 중인 PEL 항목의 idle 시간(ms)")
                .register(meterRegistry);
        Gauge.builder("analysis.stream.pending.last_check_age_ms", this, AnalysisStreamMetrics::lastCheckAgeMs)
                .description("XPENDING 조회가 마지막으로 성공한 지 지난 시간(ms). 이 값이 계속 커지면 위 두 게이지는 신뢰할 수 없다")
                .register(meterRegistry);
    }

    private long lastCheckAgeMs() {
        long last = lastSuccessfulCheckEpochMs.get();
        return last == 0 ? -1 : System.currentTimeMillis() - last;
    }

    public void recordPendingSnapshot(long count, long oldestIdleMillis) {
        pendingCount.set(count);
        oldestIdleMs.set(oldestIdleMillis);
        lastSuccessfulCheckEpochMs.set(System.currentTimeMillis());
    }

    public void recordReclaimed() {
        meterRegistry.counter("analysis.stream.reclaimed").increment();
    }

    public void recordFinalFailure() {
        meterRegistry.counter("analysis.stream.final_failures").increment();
    }

    public void recordRedisError(String operation) {
        meterRegistry.counter("analysis.stream.redis_errors", "operation", operation).increment();
    }
}
