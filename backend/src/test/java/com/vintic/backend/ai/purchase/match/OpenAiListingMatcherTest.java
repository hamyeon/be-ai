package com.vintic.backend.ai.purchase.match;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.ai.observability.domain.AiCallFailureType;
import com.vintic.backend.ai.observability.domain.AiCallLog;
import com.vintic.backend.ai.observability.domain.AiCallType;
import com.vintic.backend.ai.observability.service.AiCallLogger;
import com.vintic.backend.ai.prompt.PromptTemplateLoader;
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

// OpenAI Matcher를 실제 API 없이 가짜 클라이언트로 고정한다.
class OpenAiListingMatcherTest {

    private final ModelAliases modelAliases = new ModelAliases();
    private final AiCallLogger aiCallLogger = mock(AiCallLogger.class);
    private final MatchGoal nb990 = new MatchGoal("nb990", "New Balance 990", "New Balance", "그레이");

    @Test
    void 사용자_메시지에_Goal_별칭과_매물_텍스트가_실리고_응답은_검증기를_거친다() {
        RecordingClient client = new RecordingClient("{\"matched\":true,\"semanticScore\":0.8,\"reason\":\"품번 M990GL6로 확인\"}");
        AuctionListing listing = new AuctionListing(7L, "New Balance", null, "그레이", "뉴발란스 M990GL6 그레이 270", "x".repeat(1000));

        MatchResult result = matcher(client).evaluate(nb990, listing);

        VisionChatRequest sent = client.requests.get(0);
        assertThat(sent.userText()).contains("modelKey: nb990").contains("990v6").contains("freeTextConditions: 그레이")
                .contains("title: 뉴발란스 M990GL6 그레이 270");
        // 설명은 600자에서 자른다 - 뒤쪽은 거래 조건·브랜드 나열이 대부분이다.
        assertThat(sent.userText()).contains("x".repeat(600) + "…").doesNotContain("x".repeat(601));
        assertThat(sent.imageUrls()).isEmpty();
        assertThat(result.matched()).isTrue();
        assertThat(result.semanticScore()).isEqualTo(0.8);
        assertThat(result.reason()).isEqualTo("품번 M990GL6로 확인");

        AiCallLog log = recordedLog();
        assertThat(log.getCallType()).isEqualTo(AiCallType.CHAT);
        assertThat(log.getStage()).isEqualTo("listing-match");
        assertThat(log.isSuccess()).isTrue();
    }

    @Test
    void LLM이_일치라_해도_제목이_다른_모델이면_검증기가_제외한다() {
        RecordingClient client = new RecordingClient("{\"matched\":true,\"semanticScore\":0.9,\"reason\":\"990 계열\"}");
        AuctionListing listing = new AuctionListing(7L, "New Balance", "993", null, "뉴발란스 993 그레이 280", "");

        MatchResult result = matcher(client).evaluate(nb990, listing);

        assertThat(result.matched()).isFalse();
        assertThat(result.listingModelKey()).isEqualTo("nb993");
    }

    @Test
    void API_실패는_API_ERROR로_기록하고_규칙_대체_없이_그대로_던진다() {
        ChatCompletionClient failing = request -> {
            throw new AiApiException("OpenAI 오류 (status=429)");
        };

        assertThatThrownBy(() -> matcher(failing).evaluate(nb990,
                new AuctionListing(7L, "New Balance", "990v6", null, "뉴발란스 990v6 280", "")))
                .isInstanceOf(AiApiException.class);
        assertThat(recordedLog().getFailureType()).isEqualTo(AiCallFailureType.API_ERROR);
    }

    @Test
    void 스키마와_다른_응답은_PARSE_ERROR로_기록하고_형식_예외를_던진다() {
        RecordingClient client = new RecordingClient("{\"matched\": \"yes\"");

        assertThatThrownBy(() -> matcher(client).evaluate(nb990,
                new AuctionListing(7L, "New Balance", "990v6", null, "뉴발란스 990v6 280", "")))
                .isInstanceOf(AiResponseFormatException.class);
        assertThat(recordedLog().getFailureType()).isEqualTo(AiCallFailureType.PARSE_ERROR);
    }

    private OpenAiListingMatcher matcher(ChatCompletionClient client) {
        return new OpenAiListingMatcher(client, new ObjectMapper(), new MatchResultValidator(modelAliases),
                aiCallLogger, new ListingMatcherProperties(), new PromptTemplateLoader(), modelAliases);
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
            return new VisionChatResponse(content, 300, 30, 900);
        }
    }
}
