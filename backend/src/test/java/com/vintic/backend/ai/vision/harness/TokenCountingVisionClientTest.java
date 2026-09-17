package com.vintic.backend.ai.vision.harness;

import com.vintic.backend.ai.vision.client.VisionChatRequest;
import com.vintic.backend.ai.vision.client.VisionChatResponse;
import com.vintic.backend.ai.vision.client.VisionImageDetail;
import com.vintic.backend.ai.vision.dto.VisionAnalysisResult;
import com.vintic.backend.common.exception.AiApiException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 실제 API 없이 단계별 기록·집계가 맞는지 고정한다. 하네스는 유료라 자주 못 돌리는데,
// 집계가 틀린 채로 한 번 돌리면 그 비용이 통째로 버려진다.
class TokenCountingVisionClientTest {

    private static VisionChatRequest stageRequest(String stage, int imageCount, VisionImageDetail detail) {
        List<String> imageUrls = IntStream.range(0, imageCount)
                .mapToObj(i -> "https://example.com/%d.webp".formatted(i))
                .toList();
        return new VisionChatRequest("gpt-4o", "system", null, imageUrls, detail,
                new VisionChatRequest.ResponseSchema("vision_%s_v2".formatted(stage), "{}"), 900);
    }

    private static VisionChatResponse responseWith(int promptTokens, int completionTokens) {
        return new VisionChatResponse("{}", promptTokens, completionTokens, 0L);
    }

    @Test
    void 스키마_이름에서_단계를_읽고_스키마가_없으면_single로_본다() {
        VisionChatRequest staged = stageRequest("silhouette", 1, VisionImageDetail.LOW);
        VisionChatRequest single = new VisionChatRequest("gpt-4o", "system", null, List.of(), null, null, 900);

        assertThat(TokenCountingVisionClient.stageOf(staged)).isEqualTo("silhouette");
        assertThat(TokenCountingVisionClient.stageOf(single)).isEqualTo(TokenCountingVisionClient.SINGLE_CALL_STAGE);
    }

    @Test
    void 호출마다_단계_토큰_이미지수_detail을_기록하고_단계별로_집계한다() {
        Map<String, VisionChatResponse> responses = Map.of(
                "vision_silhouette_v2", responseWith(1_000, 100),
                "vision_label_v2", responseWith(2_000, 200),
                "vision_condition_v2", responseWith(3_000, 400));
        TokenCountingVisionClient client = new TokenCountingVisionClient(
                request -> responses.get(request.responseSchema().name()));

        for (int caseIndex = 0; caseIndex < 2; caseIndex++) {
            client.complete(stageRequest("silhouette", 3, VisionImageDetail.LOW));
            client.complete(stageRequest("label", 3, VisionImageDetail.HIGH));
            client.complete(stageRequest("condition", 3, VisionImageDetail.HIGH));
        }

        VisionHarnessReport.Usage usage = client.usage();
        assertThat(usage.apiCalls()).isEqualTo(6);
        assertThat(usage.promptTokens()).isEqualTo(12_000);
        assertThat(usage.completionTokens()).isEqualTo(1_400);
        // 단계 순서는 호출 순서를 따른다
        assertThat(usage.stages()).extracting(VisionHarnessReport.StageUsage::stage)
                .containsExactly("silhouette", "label", "condition");

        VisionHarnessReport.StageUsage condition = usage.stages().get(2);
        assertThat(condition.calls()).isEqualTo(2);
        assertThat(condition.averagePromptTokens()).isEqualTo(3_000);
        assertThat(condition.averageCompletionTokens()).isEqualTo(400);

        VisionHarnessReport.Call firstLabelCall = client.callsSince(1).get(0);
        assertThat(firstLabelCall.stage()).isEqualTo("label");
        assertThat(firstLabelCall.imageCount()).isEqualTo(3);
        assertThat(firstLabelCall.detail()).isEqualTo("high");
    }

    @Test
    void callsSince는_케이스_하나의_호출만_잘라낸다() {
        TokenCountingVisionClient client = new TokenCountingVisionClient(request -> responseWith(10, 1));
        client.complete(stageRequest("silhouette", 1, VisionImageDetail.LOW));

        int secondCaseStart = client.callCount();
        client.complete(stageRequest("silhouette", 1, VisionImageDetail.LOW));
        client.complete(stageRequest("label", 1, VisionImageDetail.HIGH));

        assertThat(client.callsSince(secondCaseStart))
                .extracting(VisionHarnessReport.Call::stage)
                .containsExactly("silhouette", "label");
    }

    @Test
    void 실패한_호출은_기록하지_않고_예외를_그대로_올린다() {
        TokenCountingVisionClient client = new TokenCountingVisionClient(request -> {
            throw new AiApiException("status=429");
        });

        assertThatThrownBy(() -> client.complete(stageRequest("label", 1, VisionImageDetail.HIGH)))
                .isInstanceOf(AiApiException.class);
        assertThat(client.callCount()).isZero();
    }

    @Test
    void 리포트에_단계별_표와_케이스별_단계_시간과_CSV가_나온다() {
        List<VisionHarnessReport.Call> calls = List.of(
                new VisionHarnessReport.Call("silhouette", 3_000L, 1_000, 100, 3, "low"),
                new VisionHarnessReport.Call("label", 1_000L, 2_000, 200, 3, "high"));
        VisionHarnessCase harnessCase = new VisionHarnessCase("case-a", List.of("https://example.com/a.webp"), null,
                new VisionHarnessCase.Expected(null, null, null, null, null, null), "테스트");
        VisionAnalysisResult result = new VisionAnalysisResult(null, null, null, null, null, null, null,
                null, null, List.of(), List.of(), List.of(), List.of());

        VisionHarnessReport report = VisionHarnessReport.aggregate("test",
                List.of(VisionHarnessScorer.score(harnessCase, result, 4_000L)),
                VisionHarnessReport.Usage.from(calls),
                Map.of("case-a", calls));

        String text = report.toText();
        assertThat(text).contains("단계별");
        // silhouette가 전체 호출 시간 4초 중 3초 = 75%
        assertThat(text).containsPattern("silhouette\\s+1\\s+3,000ms\\s+3,000ms\\s+1,000\\s+100\\s+75%");
        assertThat(text).contains("└ 단계   silhouette 3,000ms(입력 1,000/출력 100) · label 1,000ms(입력 2,000/출력 200)");

        assertThat(report.toCallsCsv().lines().toList()).containsExactly(
                "case_id,call_index,stage,latency_ms,prompt_tokens,completion_tokens,image_count,detail",
                "case-a,1,silhouette,3000,1000,100,3,low",
                "case-a,2,label,1000,2000,200,3,high");
    }
}
