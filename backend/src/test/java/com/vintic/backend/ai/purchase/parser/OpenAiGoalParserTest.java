package com.vintic.backend.ai.purchase.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.ai.observability.domain.AiCallFailureType;
import com.vintic.backend.ai.observability.domain.AiCallLog;
import com.vintic.backend.ai.observability.domain.AiCallType;
import com.vintic.backend.ai.observability.service.AiCallLogger;
import com.vintic.backend.ai.prompt.PromptTemplateLoader;
import com.vintic.backend.ai.purchase.dto.GoalCondition;
import com.vintic.backend.ai.purchase.dto.GoalDraft;
import com.vintic.backend.ai.purchase.model.ModelAliases;
import com.vintic.backend.ai.vision.client.ChatCompletionClient;
import com.vintic.backend.ai.vision.client.VisionChatRequest;
import com.vintic.backend.ai.vision.client.VisionChatResponse;
import com.vintic.backend.common.exception.AiApiException;
import com.vintic.backend.common.exception.AiResponseFormatException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

// OpenAI 파서를 실제 API 없이 가짜 클라이언트로 고정한다.
// 프롬프트에 카탈로그가 실리는지, 응답이 검증기를 거치는지, 실패가 기록되고 전파되는지.
class OpenAiGoalParserTest {

    private final ModelAliases modelAliases = new ModelAliases();
    private final AiCallLogger aiCallLogger = mock(AiCallLogger.class);

    @Test
    void 시스템_프롬프트에_카탈로그가_실리고_응답은_검증기를_거쳐_GoalDraft가_된다() {
        RecordingClient client = new RecordingClient(
                "{\"modelKey\":\"nb990\",\"modelQuery\":\"NB 990\",\"brand\":\"뉴발\",\"minCondition\":\"A\","
                        + "\"hardMaxAmount\":150000,\"sizeKr\":270,\"freeTextConditions\":null,\"confidence\":0.9}");

        GoalDraft draft = parser(client).parse("뉴발 990 A급 270 15만원 이하");

        VisionChatRequest sent = client.requests.get(0);
        assertThat(sent.systemPrompt()).contains("nb990 | New Balance 990 |").doesNotContain("{{MODEL_CATALOG}}");
        assertThat(sent.userText()).isEqualTo("뉴발 990 A급 270 15만원 이하");
        assertThat(sent.imageUrls()).isEmpty();
        assertThat(sent.responseSchema().name()).isEqualTo("purchase_goal_parse_v1");
        // 검증기가 카탈로그 값으로 덮어쓴다 - LLM이 쓴 "NB 990"/"뉴발"이 아니다.
        assertThat(draft.modelQuery()).isEqualTo("New Balance 990");
        assertThat(draft.brand()).isEqualTo("New Balance");
        assertThat(draft.minCondition()).isEqualTo(GoalCondition.A);
        assertThat(draft.hardMaxAmount()).isEqualTo(150_000L);

        AiCallLog log = recordedLog();
        assertThat(log.getCallType()).isEqualTo(AiCallType.CHAT);
        assertThat(log.getStage()).isEqualTo("goal-parse");
        assertThat(log.isSuccess()).isTrue();
        assertThat(log.getPromptTokens()).isEqualTo(120);
    }

    @Test
    void API_실패는_API_ERROR로_기록하고_그대로_던진다() {
        ChatCompletionClient failing = request -> {
            throw new AiApiException("OpenAI 오류 (status=503)");
        };

        assertThatThrownBy(() -> parser(failing).parse("뉴발 990"))
                .isInstanceOf(AiApiException.class)
                .hasMessageContaining("503");
        AiCallLog log = recordedLog();
        assertThat(log.isSuccess()).isFalse();
        assertThat(log.getFailureType()).isEqualTo(AiCallFailureType.API_ERROR);
    }

    @Test
    void 스키마와_다른_응답은_PARSE_ERROR로_기록하고_형식_예외를_던진다() {
        RecordingClient client = new RecordingClient("{\"modelKey\": \"nb990\", \"hardMaxAmount\": \"십오만\"");

        assertThatThrownBy(() -> parser(client).parse("뉴발 990"))
                .isInstanceOf(AiResponseFormatException.class);
        AiCallLog log = recordedLog();
        assertThat(log.getFailureType()).isEqualTo(AiCallFailureType.PARSE_ERROR);
    }

    private OpenAiGoalParser parser(ChatCompletionClient client) {
        return new OpenAiGoalParser(client, new ObjectMapper(), new GoalDraftValidator(modelAliases),
                aiCallLogger, new GoalParserProperties(), new PromptTemplateLoader(), modelAliases);
    }

    private AiCallLog recordedLog() {
        ArgumentCaptor<AiCallLog> captor = ArgumentCaptor.forClass(AiCallLog.class);
        verify(aiCallLogger).record(captor.capture());
        return captor.getValue();
    }

    private static final class RecordingClient implements ChatCompletionClient {
        private final String content;
        private final List<VisionChatRequest> requests = new ArrayList<>();

        private RecordingClient(String content) {
            this.content = content;
        }

        @Override
        public VisionChatResponse complete(VisionChatRequest request) {
            requests.add(request);
            return new VisionChatResponse(content, 120, 40, 800);
        }
    }
}
