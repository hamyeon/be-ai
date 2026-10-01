package com.vintic.backend.ai.purchase.match;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.ai.observability.domain.AiCallFailureType;
import com.vintic.backend.ai.observability.domain.AiCallLog;
import com.vintic.backend.ai.observability.domain.AiCallType;
import com.vintic.backend.ai.observability.service.AiCallLogger;
import com.vintic.backend.ai.observability.service.AiCallRequestSummary;
import com.vintic.backend.ai.prompt.PromptTemplateLoader;
import com.vintic.backend.ai.purchase.model.ModelAliases;
import com.vintic.backend.ai.vision.client.ChatCompletionClient;
import com.vintic.backend.ai.vision.client.VisionChatRequest;
import com.vintic.backend.ai.vision.client.VisionChatResponse;
import com.vintic.backend.ai.vision.client.VisionImageDetail;
import com.vintic.backend.common.exception.AiResponseFormatException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

// LLM Matcher. Goal 한 개 × 매물 한 개를 텍스트로 넘겨 matched/semanticScore/reason을 받는다.
//
// 사용자 메시지에 Goal의 카탈로그 별칭을 함께 실어 "이 표기들이 전부 같은 모델"임을 알려준다.
// 결과는 MatchResultValidator가 검증한다 - 규칙이 확실히 아는 것(다른 모델, 박스만, 아동)은
// LLM이 뭐라 하든 제외된다.
//
// 실패는 예외로 올린다(ListingMatcher 계약). 여기서 규칙 기반으로 대체하지 않는다.
@Component
@Slf4j
public class OpenAiListingMatcher implements ListingMatcher {

    private static final String PROMPT_CATEGORY = "purchase";
    private static final String PROMPT_NAME = "listing-match";
    private static final String PROMPT_VERSION = "v1";
    private static final String STAGE = "listing-match";
    private static final int MAX_DESCRIPTION_CHARS = 600;

    private final ChatCompletionClient chatClient;
    private final ObjectMapper objectMapper;
    private final MatchResultValidator validator;
    private final AiCallLogger aiCallLogger;
    private final ListingMatcherProperties properties;
    private final ModelAliases modelAliases;
    private final String systemPrompt;
    private final VisionChatRequest.ResponseSchema responseSchema;

    public OpenAiListingMatcher(
            ChatCompletionClient chatClient,
            ObjectMapper objectMapper,
            MatchResultValidator validator,
            AiCallLogger aiCallLogger,
            ListingMatcherProperties properties,
            PromptTemplateLoader promptTemplateLoader,
            ModelAliases modelAliases
    ) {
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.aiCallLogger = aiCallLogger;
        this.properties = properties;
        this.modelAliases = modelAliases;
        this.systemPrompt = promptTemplateLoader.load(PROMPT_CATEGORY, PROMPT_NAME, PROMPT_VERSION).content();
        this.responseSchema = new VisionChatRequest.ResponseSchema(
                "purchase_listing_match_%s".formatted(PROMPT_VERSION),
                promptTemplateLoader.loadSchema(PROMPT_CATEGORY, PROMPT_NAME, PROMPT_VERSION));
    }

    @Override
    public MatchResult evaluate(MatchGoal goal, AuctionListing listing) {
        VisionChatRequest request = new VisionChatRequest(
                properties.getModel(),
                systemPrompt,
                renderUserText(goal, listing),
                List.of(),
                VisionImageDetail.LOW,
                responseSchema,
                properties.getMaxOutputTokens()
        );
        String requestSummary = AiCallRequestSummary.of(request, objectMapper);

        VisionChatResponse response;
        long startedAt = System.currentTimeMillis();
        try {
            response = chatClient.complete(request);
        } catch (RuntimeException e) {
            aiCallLogger.record(logBuilder(requestSummary)
                    .latencyMs(System.currentTimeMillis() - startedAt)
                    .failure(AiCallFailureType.API_ERROR, e.getMessage())
                    .build());
            throw e;
        }

        try {
            LlmMatchResult llm = objectMapper.readValue(response.content(), LlmMatchResult.class);
            aiCallLogger.record(logBuilder(requestSummary)
                    .latencyMs(response.latencyMs())
                    .tokens(response.promptTokens(), response.completionTokens())
                    .responseBody(response.content())
                    .build());
            return validator.validate(llm, goal, listing);
        } catch (Exception e) {
            aiCallLogger.record(logBuilder(requestSummary)
                    .latencyMs(response.latencyMs())
                    .tokens(response.promptTokens(), response.completionTokens())
                    .responseBody(response.content())
                    .failure(AiCallFailureType.PARSE_ERROR, e.getMessage())
                    .build());
            throw new AiResponseFormatException("매물 적합도 응답을 처리하는 중 오류가 발생했습니다.", e);
        }
    }

    private String renderUserText(MatchGoal goal, AuctionListing listing) {
        StringBuilder out = new StringBuilder();
        out.append("[Goal]\n");
        if (goal.modelKey() != null) {
            out.append("modelKey: ").append(goal.modelKey()).append('\n');
            out.append("model: ").append(goal.modelQuery()).append('\n');
            out.append("known spellings of this model: ")
                    .append(String.join(", ", modelAliases.aliasesOf(goal.modelKey()))).append('\n');
        } else {
            out.append("model: (not specified - judge brand only)\n");
        }
        out.append("brand: ").append(nullToDash(goal.brand())).append('\n');
        out.append("freeTextConditions: ").append(nullToDash(goal.freeTextConditions())).append('\n');
        out.append("\n[Listing]\n");
        out.append("brand: ").append(nullToDash(listing.brand())).append('\n');
        out.append("model: ").append(nullToDash(listing.model())).append('\n');
        out.append("colorway: ").append(nullToDash(listing.colorway())).append('\n');
        out.append("title: ").append(nullToDash(listing.title())).append('\n');
        out.append("description: ").append(truncate(listing.description())).append('\n');
        return out.toString();
    }

    // 설명란 뒤쪽은 거래 조건·검색용 브랜드 나열이 대부분이라 토큰만 쓴다.
    private String truncate(String description) {
        if (description == null || description.isBlank()) {
            return "-";
        }
        String compact = description.replaceAll("\\s+", " ").trim();
        return compact.length() <= MAX_DESCRIPTION_CHARS ? compact : compact.substring(0, MAX_DESCRIPTION_CHARS) + "…";
    }

    private String nullToDash(String value) {
        return value == null || value.isBlank() ? "-" : value.trim();
    }

    private AiCallLog.Builder logBuilder(String requestSummary) {
        return AiCallLog.builder(AiCallType.CHAT, properties.getModel())
                .stage(STAGE)
                .promptVersion(PROMPT_VERSION)
                .requestSummary(requestSummary);
    }
}
