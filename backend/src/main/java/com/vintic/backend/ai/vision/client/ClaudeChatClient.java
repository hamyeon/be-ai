package com.vintic.backend.ai.vision.client;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlockParam;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.common.exception.AiApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Claude Messages API를 ChatCompletionClient 계약으로 호출하는 두 번째 구현체.
//
// OpenAiVisionClient와 같은 요청/응답 값 객체를 받고 돌려주므로 호출부(Vision 3단계, Goal 파서,
// Matcher)는 어느 벤더인지 모른다. 벤더별 차이는 전부 이 클래스 안에 갇힌다:
//
//  - 이미지 해상도(detail): Claude에는 대응 파라미터가 없다. 긴 변 약 1568px로 자동 축소되고
//    토큰은 픽셀 수에 비례한다. 그래서 detail은 무시하고 URL을 그대로 보낸다. OpenAI의
//    low(512px 고정)와 같은 조건이 아니라는 점은 하네스 비교 때 감안해야 한다 - OpenAI 쪽을
//    -Dvision.harness.detail=high로 맞춰 재는 게 공정하다.
//  - Structured Outputs: output_config.format(json_schema). 스키마 파일은 표준 JSON Schema라
//    그대로 싣는다. responseSchema가 null(v1의 json_object 모드)이면 형식 강제 없이 부른다 -
//    Claude에는 json_object 모드가 없고, v1 프롬프트가 JSON을 요구하므로 그걸로 충분하다.
//  - max_tokens: thinking 토큰이 포함되므로 호출부 값에 여유분을 더한다(ClaudeClientProperties).
//  - stop_reason: max_tokens(잘림)와 refusal(안전 분류기 거절)은 OpenAI의 finish_reason=length,
//    refusal 처리와 같은 자리에서 AiApiException으로 올린다.
//  - 재시도: SDK가 429/5xx/연결 오류를 retry-after를 읽어 재시도한다. 직접 구현하지 않는다.
//
// 키가 없어도 빈은 뜬다. OpenAI만 쓰는 환경에서 Claude 키 부재가 기동 실패가 되면 안 되기
// 때문이다. 대신 첫 호출에서 명확한 메시지로 실패한다.
@Service
@Slf4j
public class ClaudeChatClient implements ChatCompletionClient {

    private final ObjectMapper objectMapper;
    private final ClaudeClientProperties properties;

    // 지연 생성. 테스트·하네스는 다른 생성자로 미리 만든 클라이언트를 넣는다.
    private volatile AnthropicClient client;

    // 생성자가 둘이라 Spring이 어느 것을 쓸지 알 수 있게 표시한다.
    @Autowired
    public ClaudeChatClient(ObjectMapper objectMapper, ClaudeClientProperties properties) {
        this(objectMapper, properties, null);
    }

    ClaudeChatClient(ObjectMapper objectMapper, ClaudeClientProperties properties, AnthropicClient client) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.client = client;
    }

    @Override
    public VisionChatResponse complete(VisionChatRequest request) {
        MessageCreateParams params = buildParams(request);

        long startedAt = System.currentTimeMillis();
        Message message = callMessagesApi(params, request);
        long latencyMs = System.currentTimeMillis() - startedAt;

        return toResponse(message, latencyMs);
    }

    // 요청 값 객체 → Messages API 파라미터. 테스트가 이 매핑만 따로 검증할 수 있게 분리했다.
    MessageCreateParams buildParams(VisionChatRequest request) {
        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .model(request.model())
                .maxTokens((long) request.maxOutputTokens() + properties.getOutputTokenAllowance())
                .system(request.systemPrompt())
                .addUserMessageOfBlockParams(buildUserContent(request));

        OutputConfig outputConfig = buildOutputConfig(request.responseSchema());
        if (outputConfig != null) {
            builder.outputConfig(outputConfig);
        }
        return builder.build();
    }

    private List<ContentBlockParam> buildUserContent(VisionChatRequest request) {
        List<ContentBlockParam> content = new ArrayList<>();

        // OpenAI 구현과 같은 순서 - 이전 단계 결과를 이미지보다 먼저 넣어 맥락을 먼저 잡게 한다.
        if (request.userText() != null && !request.userText().isBlank()) {
            content.add(ContentBlockParam.ofText(TextBlockParam.builder().text(request.userText()).build()));
        }
        for (String imageUrl : request.imageUrls()) {
            content.add(ContentBlockParam.ofImage(ImageBlockParam.builder().urlSource(imageUrl).build()));
        }
        if (content.isEmpty()) {
            // Messages API는 빈 user 메시지를 거부한다. 텍스트도 이미지도 없으면 시스템 프롬프트만으로
            // 답하라는 뜻이므로 최소한의 지시 한 줄을 넣는다.
            content.add(ContentBlockParam.ofText(TextBlockParam.builder().text("Respond as instructed.").build()));
        }
        return content;
    }

    private OutputConfig buildOutputConfig(VisionChatRequest.ResponseSchema responseSchema) {
        OutputConfig.Builder builder = OutputConfig.builder();
        boolean hasAnything = false;

        if (responseSchema != null) {
            builder.format(JsonOutputFormat.builder().schema(readSchema(responseSchema)).build());
            hasAnything = true;
        }
        if (properties.getEffort() != null && !properties.getEffort().isBlank()) {
            builder.effort(OutputConfig.Effort.of(properties.getEffort().trim().toLowerCase()));
            hasAnything = true;
        }
        return hasAnything ? builder.build() : null;
    }

    private JsonOutputFormat.Schema readSchema(VisionChatRequest.ResponseSchema responseSchema) {
        Map<String, Object> schema;
        try {
            schema = objectMapper.readValue(responseSchema.schemaJson(), new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception e) {
            throw new AiApiException("Vision 응답 스키마가 올바른 JSON이 아닙니다: " + responseSchema.name(), e);
        }
        JsonOutputFormat.Schema.Builder builder = JsonOutputFormat.Schema.builder();
        schema.forEach((key, value) -> builder.putAdditionalProperty(key, JsonValue.from(value)));
        return builder.build();
    }

    private Message callMessagesApi(MessageCreateParams params, VisionChatRequest request) {
        try {
            return client().messages().create(params);
        } catch (AnthropicServiceException e) {
            // 주의: 예외에 담긴 헤더에는 API 키가 있을 수 있다. 상태 코드와 본문만 남긴다.
            log.error("Claude API가 오류 응답을 반환했습니다. model={}, status={}, body={}",
                    request.model(), e.statusCode(), compact(String.valueOf(e.body())));
            throw new AiApiException(
                    "Claude API 오류 (status=%d): %s".formatted(e.statusCode(), compact(String.valueOf(e.body()))), e);
        } catch (AnthropicException e) {
            log.error("Claude API 호출 중 오류가 발생했습니다. exceptionType={}, message={}",
                    e.getClass().getSimpleName(), e.getMessage());
            throw new AiApiException("Claude API 호출 중 오류가 발생했습니다: " + e.getMessage(), e);
        }
    }

    // 응답 → 값 객체. 테스트가 이 매핑만 따로 검증할 수 있게 분리했다.
    VisionChatResponse toResponse(Message message, long latencyMs) {
        StopReason stopReason = message.stopReason().orElse(null);

        // Structured Outputs를 쓰더라도 max_tokens에 걸려 잘리면 불완전한 JSON이 온다.
        // 파싱 단계에서 애매하게 실패하기 전에 여기서 끊는다. (OpenAI의 finish_reason=length와 같은 처리)
        if (stopReason != null && stopReason.equals(StopReason.MAX_TOKENS)) {
            throw new AiApiException("Claude 응답이 max_tokens 한도에 걸려 잘렸습니다.");
        }
        if (stopReason != null && stopReason.equals(StopReason.REFUSAL)) {
            String detail = message.stopDetails()
                    .map(details -> details.category().map(Object::toString).orElse("unknown")
                            + details.explanation().map(explanation -> ": " + explanation).orElse(""))
                    .orElse("사유 없음");
            throw new AiApiException("Claude 모델이 응답을 거부했습니다: " + detail);
        }

        // thinking 블록(표시 생략 모드에서는 빈 문자열)은 건너뛰고 text 블록만 이어 붙인다.
        StringBuilder text = new StringBuilder();
        for (ContentBlock block : message.content()) {
            block.text().ifPresent(textBlock -> text.append(textBlock.text()));
        }
        if (text.isEmpty()) {
            throw new AiApiException("Claude 응답에 텍스트 블록이 없습니다. stopReason="
                    + (stopReason == null ? "null" : stopReason.asString()));
        }

        return new VisionChatResponse(
                text.toString(),
                (int) message.usage().inputTokens(),
                (int) message.usage().outputTokens(),
                latencyMs
        );
    }

    private AnthropicClient client() {
        AnthropicClient existing = client;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (client == null) {
                if (!properties.hasApiKey()) {
                    throw new AiApiException("Claude API 키가 없습니다. ANTHROPIC_API_KEY(anthropic.api.key)를 설정하세요.");
                }
                client = AnthropicOkHttpClient.builder()
                        .apiKey(properties.getApi().getKey())
                        .maxRetries(properties.getMaxRetries())
                        .build();
            }
            return client;
        }
    }

    // 오류 본문을 한 줄로 눌러 담는다. OpenAiVisionClient와 같은 이유 - 반복되는 오류가 로그를 덮지 않게.
    private String compact(String body) {
        if (body == null) {
            return null;
        }
        String oneLine = body.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 300 ? oneLine.substring(0, 300) + "…" : oneLine;
    }
}
