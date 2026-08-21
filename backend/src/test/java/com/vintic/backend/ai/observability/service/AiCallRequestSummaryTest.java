package com.vintic.backend.ai.observability.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.ai.vision.client.VisionChatRequest;
import com.vintic.backend.ai.vision.client.VisionImageDetail;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AiCallRequestSummaryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private VisionChatRequest request() {
        return new VisionChatRequest(
                "gpt-4o",
                "당신은 신발 감정 전문가입니다. 시스템 프롬프트 본문",
                "1단계 결과: Nike",
                List.of("https://bucket.s3.amazonaws.com/a.jpg"),
                VisionImageDetail.HIGH,
                new VisionChatRequest.ResponseSchema("vision_label_v2", "{}"),
                1000
        );
    }

    @Test
    void 호출_옵션과_이미지_정보를_담는다() {
        String summary = AiCallRequestSummary.of(request(), objectMapper);

        assertThat(summary).contains("gpt-4o", "high", "vision_label_v2", "a.jpg");
        assertThat(summary).contains("1단계 결과: Nike");
    }

    @Test
    void 시스템_프롬프트_본문은_담지_않는다() {
        // 매 호출마다 같은 값이 수 KB씩 반복 저장될 뿐이다. promptVersion으로 되짚을 수 있다.
        String summary = AiCallRequestSummary.of(request(), objectMapper);

        assertThat(summary).doesNotContain("시스템 프롬프트 본문");
    }

    @Test
    void 임베딩_요약은_모델과_입력을_담는다() {
        String summary = AiCallRequestSummary.ofEmbedding(
                "text-embedding-3-small", "Nike Dunk Low Panda", objectMapper);

        assertThat(summary).contains("text-embedding-3-small", "Nike Dunk Low Panda");
    }
}
