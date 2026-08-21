package com.vintic.backend.ai.observability.service;

import com.vintic.backend.ai.observability.domain.AiCallLog;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

// AI 호출을 Micrometer 지표로 집계한다.
//
// 같은 값이 ai_call_logs 테이블에도 쌓이는데 왜 또 재느냐면, 용도가 다르기 때문이다.
// 테이블은 "저 분석이 왜 저렇게 나왔는지" 한 건을 되짚는 용도라 응답 본문까지 담고 있고,
// 지표는 "지금 실패율이 튀는지"를 훑는 용도라 숫자만 있으면 된다.
// 후자를 SQL로 하면 매번 수십만 행을 집계해야 한다.
//
// 태그를 호출 종류·단계·프롬프트 버전으로 잡았다. "v2로 바꾸고 나서 label 단계가 느려졌나"
// 같은 질문에 답하려면 이 셋으로 쪼갤 수 있어야 한다.
//
// 주의: 태그 값은 코드가 정하는 값(enum, 단계명, 버전)만 쓴다. 실패 메시지처럼 값의 종류가
// 무한한 걸 태그로 넣으면 시계열이 폭발한다.
@Component
public class AiCallMetrics {

    private static final String CALL_COUNTER = "ai.calls";
    private static final String LATENCY_TIMER = "ai.call.latency";
    private static final String TOKEN_COUNTER = "ai.tokens";

    private final MeterRegistry meterRegistry;

    public AiCallMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void record(AiCallLog callLog) {
        String type = callLog.getCallType().name();
        String stage = callLog.getStage() == null ? "none" : callLog.getStage();
        String promptVersion = callLog.getPromptVersion() == null ? "none" : callLog.getPromptVersion();
        // 실패 사유는 종류(enum)까지만. 메시지는 태그로 쓰지 않는다.
        String outcome = callLog.isSuccess() ? "success" : callLog.getFailureType().name();

        meterRegistry.counter(CALL_COUNTER,
                "type", type, "stage", stage, "prompt_version", promptVersion, "outcome", outcome).increment();

        Timer.builder(LATENCY_TIMER)
                .tags("type", type, "stage", stage, "prompt_version", promptVersion)
                .register(meterRegistry)
                .record(callLog.getLatencyMs(), TimeUnit.MILLISECONDS);

        // 비용은 토큰에 비례한다. 정확도가 올라도 토큰이 몇 배로 뛰면 채택할 수 없다.
        if (callLog.totalTokens() > 0) {
            meterRegistry.counter(TOKEN_COUNTER, "type", type, "stage", stage, "prompt_version", promptVersion)
                    .increment(callLog.totalTokens());
        }
    }
}
