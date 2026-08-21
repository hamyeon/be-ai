package com.vintic.backend.ai.observability.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiCallLogTest {

    @Test
    void 성공_기록은_실패_정보가_비어있다() {
        AiCallLog log = AiCallLog.builder(AiCallType.VISION, "gpt-4o")
                .stage("silhouette")
                .promptVersion("v2")
                .analysisId(7L)
                .latencyMs(1200)
                .tokens(800, 120)
                .build();

        assertThat(log.isSuccess()).isTrue();
        assertThat(log.getFailureType()).isNull();
        assertThat(log.totalTokens()).isEqualTo(920);
        assertThat(log.getCreatedAt()).isNotNull();
    }

    @Test
    void 실패를_지정하면_성공_여부가_뒤집힌다() {
        AiCallLog log = AiCallLog.builder(AiCallType.VISION, "gpt-4o")
                .failure(AiCallFailureType.PARSE_ERROR, "응답이 잘렸습니다")
                .build();

        assertThat(log.isSuccess()).isFalse();
        assertThat(log.getFailureType()).isEqualTo(AiCallFailureType.PARSE_ERROR);
    }

    @Test
    void 긴_응답_본문은_잘라서_담는다() {
        // 비정상 응답이 들어와도 행 하나가 DB를 압박하면 안 된다.
        String huge = "x".repeat(50_000);

        AiCallLog log = AiCallLog.builder(AiCallType.VISION, "gpt-4o").responseBody(huge).build();

        assertThat(log.getResponseBody()).hasSizeLessThan(huge.length());
        assertThat(log.getResponseBody()).contains("생략");
    }

    @Test
    void 짧은_본문은_그대로_둔다() {
        AiCallLog log = AiCallLog.builder(AiCallType.VISION, "gpt-4o").responseBody("{\"brand\":\"Nike\"}").build();

        assertThat(log.getResponseBody()).isEqualTo("{\"brand\":\"Nike\"}");
    }

    @Test
    void 모델명이_없으면_만들_수_없다() {
        assertThatThrownBy(() -> AiCallLog.builder(AiCallType.VISION, " "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
