package com.vintic.backend.ai.purchase.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.ai.observability.domain.AiCallFailureType;
import com.vintic.backend.ai.observability.domain.AiCallLog;
import com.vintic.backend.ai.observability.domain.AiCallType;
import com.vintic.backend.ai.observability.service.AiCallLogger;
import com.vintic.backend.ai.observability.service.AiCallRequestSummary;
import com.vintic.backend.ai.prompt.PromptTemplate;
import com.vintic.backend.ai.prompt.PromptTemplateLoader;
import com.vintic.backend.ai.purchase.dto.GoalDraft;
import com.vintic.backend.ai.purchase.model.ModelAliases;
import com.vintic.backend.ai.vision.client.ChatCompletionClient;
import com.vintic.backend.ai.vision.client.VisionChatRequest;
import com.vintic.backend.ai.vision.client.VisionChatResponse;
import com.vintic.backend.ai.vision.client.VisionImageDetail;
import com.vintic.backend.common.exception.AiResponseFormatException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

// LLM Goal 파서. 자연어 한 문장을 Structured Outputs로 LlmGoalDraft로 받고 서버가 검증한다.
//
// 시스템 프롬프트에 시세 카탈로그(45개 모델의 키·이름·별칭)를 실어 보낸다. 모델이 "뉴발 990"을
// nb990으로 접는 일을 프롬프트가 하고, 카탈로그 밖 모델은 modelKey=null로 두게 한다.
// 그래도 엉뚱한 키가 오면 GoalDraftValidator가 걷어낸다 - LLM 값이 검증 없이 나가는 길은 없다.
//
// 이미지는 보내지 않는다(텍스트 전용). Vision 클라이언트를 재사용하는 이유는 재시도·Structured
// Outputs·토큰 집계가 이미 거기 있어서다. detail은 이미지가 없으니 의미 없지만 값 객체가
// non-null을 요구해 LOW를 넣는다.
@Component
@Slf4j
public class OpenAiGoalParser implements GoalParser {

    private static final String PROMPT_CATEGORY = "purchase";
    private static final String PROMPT_NAME = "goal-parse";
    private static final String PROMPT_VERSION = "v1";
    private static final String STAGE = "goal-parse";
    private static final String CATALOG_PLACEHOLDER = "{{MODEL_CATALOG}}";

    private final ChatCompletionClient chatClient;
    private final ObjectMapper objectMapper;
    private final GoalDraftValidator validator;
    private final AiCallLogger aiCallLogger;
    private final GoalParserProperties properties;
    private final String systemPrompt;
    private final VisionChatRequest.ResponseSchema responseSchema;

    public OpenAiGoalParser(
            ChatCompletionClient chatClient,
            ObjectMapper objectMapper,
            GoalDraftValidator validator,
            AiCallLogger aiCallLogger,
            GoalParserProperties properties,
            PromptTemplateLoader promptTemplateLoader,
            ModelAliases modelAliases
    ) {
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.aiCallLogger = aiCallLogger;
        this.properties = properties;

        // 프롬프트·스키마·카탈로그는 배포 중에 바뀌지 않으므로 기동 시 한 번만 만든다.
        PromptTemplate template = promptTemplateLoader.load(PROMPT_CATEGORY, PROMPT_NAME, PROMPT_VERSION);
        this.systemPrompt = template.content().replace(CATALOG_PLACEHOLDER, renderCatalog(modelAliases));
        this.responseSchema = new VisionChatRequest.ResponseSchema(
                "purchase_goal_parse_%s".formatted(PROMPT_VERSION),
                promptTemplateLoader.loadSchema(PROMPT_CATEGORY, PROMPT_NAME, PROMPT_VERSION));
    }

    @Override
    public GoalDraft parse(String naturalLanguage) {
        VisionChatRequest request = new VisionChatRequest(
                properties.getModel(),
                systemPrompt,
                naturalLanguage,
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
            LlmGoalDraft llm = objectMapper.readValue(response.content(), LlmGoalDraft.class);
            aiCallLogger.record(logBuilder(requestSummary)
                    .latencyMs(response.latencyMs())
                    .tokens(response.promptTokens(), response.completionTokens())
                    .responseBody(response.content())
                    .build());
            log.info("Goal 파싱 완료 - model={}, promptTokens={}, completionTokens={}, latencyMs={}",
                    properties.getModel(), response.promptTokens(), response.completionTokens(), response.latencyMs());
            return validator.validate(llm);
        } catch (Exception e) {
            aiCallLogger.record(logBuilder(requestSummary)
                    .latencyMs(response.latencyMs())
                    .tokens(response.promptTokens(), response.completionTokens())
                    .responseBody(response.content())
                    .failure(AiCallFailureType.PARSE_ERROR, e.getMessage())
                    .build());
            throw new AiResponseFormatException("Goal 파싱 응답을 처리하는 중 오류가 발생했습니다.", e);
        }
    }

    private AiCallLog.Builder logBuilder(String requestSummary) {
        return AiCallLog.builder(AiCallType.CHAT, properties.getModel())
                .stage(STAGE)
                .promptVersion(PROMPT_VERSION)
                .requestSummary(requestSummary);
    }

    // "nb990 | New Balance 990 | 뉴발란스 990, 뉴발 990, 990v6, ..." 한 줄씩.
    private String renderCatalog(ModelAliases modelAliases) {
        return modelAliases.catalog().stream()
                .map(model -> "%s | %s | %s".formatted(
                        model.modelKey(), model.fullName(), String.join(", ", modelAliases.aliasesOf(model.modelKey()))))
                .collect(Collectors.joining("\n"));
    }
}
