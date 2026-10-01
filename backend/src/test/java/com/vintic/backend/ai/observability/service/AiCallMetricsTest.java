package com.vintic.backend.ai.observability.service;

import com.vintic.backend.ai.observability.domain.AiCallFailureType;
import com.vintic.backend.ai.observability.domain.AiCallLog;
import com.vintic.backend.ai.observability.domain.AiCallType;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiCallMetricsTest {

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final AiCallMetrics metrics = new AiCallMetrics(registry);

    private AiCallLog.Builder vision(String stage) {
        return AiCallLog.builder(AiCallType.VISION, "gpt-4o").stage(stage).promptVersion("v2");
    }

    @Test
    void 성공과_실패를_구분해_센다() {
        metrics.record(vision("label").latencyMs(1000).build());
        metrics.record(vision("label").latencyMs(500)
                .failure(AiCallFailureType.PARSE_ERROR, "잘림").build());

        assertThat(registry.counter("ai.calls", "type", "VISION", "stage", "label",
                "prompt_version", "v2", "outcome", "success").count()).isEqualTo(1.0);
        assertThat(registry.counter("ai.calls", "type", "VISION", "stage", "label",
                "prompt_version", "v2", "outcome", "PARSE_ERROR").count()).isEqualTo(1.0);
    }

    @Test
    void 단계별로_응답시간을_나눠_잰다() {
        // "v2로 바꾸고 나서 label 단계가 느려졌나"에 답하려면 단계로 쪼갤 수 있어야 한다.
        metrics.record(vision("silhouette").latencyMs(1000).build());
        metrics.record(vision("label").latencyMs(3000).build());

        assertThat(registry.timer("ai.call.latency", "type", "VISION", "stage", "silhouette",
                "prompt_version", "v2").totalTime(java.util.concurrent.TimeUnit.MILLISECONDS))
                .isEqualTo(1000.0);
        assertThat(registry.timer("ai.call.latency", "type", "VISION", "stage", "label",
                "prompt_version", "v2").totalTime(java.util.concurrent.TimeUnit.MILLISECONDS))
                .isEqualTo(3000.0);
    }

    @Test
    void 토큰_사용량을_누적한다() {
        metrics.record(vision("label").latencyMs(100).tokens(1000, 200).build());
        metrics.record(vision("label").latencyMs(100).tokens(500, 100).build());

        assertThat(registry.counter("ai.tokens", "type", "VISION", "stage", "label",
                "prompt_version", "v2").count()).isEqualTo(1800.0);
    }

    @Test
    void 단계가_없는_호출도_집계된다() {
        // 임베딩은 단계도 프롬프트 버전도 없다. 태그가 null이면 Micrometer가 예외를 던진다.
        metrics.record(AiCallLog.builder(AiCallType.EMBEDDING, "text-embedding-3-small")
                .latencyMs(200).tokens(29, 0).build());

        assertThat(registry.counter("ai.calls", "type", "EMBEDDING", "stage", "none",
                "prompt_version", "none", "outcome", "success").count()).isEqualTo(1.0);
    }

    @Test
    void 실패_메시지는_태그로_쓰지_않는다() {
        // 값의 종류가 무한한 걸 태그로 넣으면 시계열이 폭발한다.
        metrics.record(vision("label").latencyMs(100)
                .failure(AiCallFailureType.API_ERROR, "status=429 요청이 많습니다").build());

        assertThat(registry.getMeters())
                .allSatisfy(meter -> assertThat(meter.getId().getTags())
                        .noneMatch(tag -> tag.getValue().contains("429")));
    }
}
