package com.vintic.backend.ai.vision.client;

import com.anthropic.core.JsonValue;
import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.common.exception.AiApiException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 실제 API를 부르지 않고, 요청 값 객체 → Messages API 파라미터 매핑과 응답 → 값 객체 매핑만 고정한다.
// 재시도·전송은 SDK 몫이라 여기서 검증하지 않는다.
// 응답 픽스처는 실제 wire 형식의 JSON을 SDK 매퍼로 읽어 만든다 - 빌더로 조립하면 wire에서는
// 생략 가능한 필드까지 전부 채워야 하고, 실제 응답과 모양이 어긋나기 쉽다.
class ClaudeChatClientTest {

    private static final String SCHEMA_JSON = """
            {"type":"object","additionalProperties":false,"required":["brand"],
             "properties":{"brand":{"type":["string","null"]}}}
            """;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ClaudeChatClient newClient(ClaudeClientProperties properties) {
        return new ClaudeChatClient(objectMapper, properties);
    }

    private ClaudeClientProperties propertiesWithKey() {
        ClaudeClientProperties properties = new ClaudeClientProperties();
        properties.getApi().setKey("test-key");
        return properties;
    }

    private VisionChatRequest request(String userText, VisionChatRequest.ResponseSchema schema) {
        return new VisionChatRequest(
                "claude-opus-5", "system prompt", userText,
                List.of("https://example.com/a.jpg", "https://example.com/b.jpg"),
                VisionImageDetail.HIGH, schema, 900);
    }

    private Message messageOf(String stopReason, String contentJson) {
        return messageOf(stopReason, contentJson, "null");
    }

    private Message messageOf(String stopReason, String contentJson, String stopDetailsJson) {
        String json = """
                {"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5",
                 "content":%s,"stop_reason":"%s","stop_sequence":null,"stop_details":%s,
                 "usage":{"input_tokens":1200,"output_tokens":300}}
                """.formatted(contentJson, stopReason, stopDetailsJson);
        try {
            return ObjectMappers.jsonMapper().readValue(json, Message.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void 요청을_모델_시스템프롬프트_텍스트_이미지_순서의_Messages_파라미터로_옮긴다() {
        MessageCreateParams params = newClient(propertiesWithKey())
                .buildParams(request("1단계 결과", new VisionChatRequest.ResponseSchema("vision_test", SCHEMA_JSON)));

        assertThat(params.model().asString()).isEqualTo("claude-opus-5");
        assertThat(params.system()).isPresent();
        assertThat(params.system().get().asString()).isEqualTo("system prompt");

        List<MessageParam> messages = params.messages();
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).role()).isEqualTo(MessageParam.Role.USER);

        List<ContentBlockParam> blocks = messages.get(0).content().asBlockParams();
        assertThat(blocks).hasSize(3);
        assertThat(blocks.get(0).isText()).isTrue();
        assertThat(blocks.get(0).asText().text()).isEqualTo("1단계 결과");
        assertThat(blocks.get(1).isImage()).isTrue();
        assertThat(blocks.get(1).asImage().source().asUrl().url()).isEqualTo("https://example.com/a.jpg");
        assertThat(blocks.get(2).asImage().source().asUrl().url()).isEqualTo("https://example.com/b.jpg");
    }

    @Test
    void 응답_스키마는_output_config_format에_JSON_Schema_그대로_실린다() {
        MessageCreateParams params = newClient(propertiesWithKey())
                .buildParams(request(null, new VisionChatRequest.ResponseSchema("vision_test", SCHEMA_JSON)));

        assertThat(params.outputConfig()).isPresent();
        assertThat(params.outputConfig().get().format()).isPresent();
        Map<String, JsonValue> schema = params.outputConfig().get().format().get().schema()._additionalProperties();
        assertThat(schema.get("type").asString()).contains("object");
        assertThat(schema.get("required").asArray()).isPresent();
        @SuppressWarnings("unchecked")
        Map<String, JsonValue> properties = (Map<String, JsonValue>) schema.get("properties").asObject().orElseThrow();
        assertThat(properties).containsKey("brand");
        // effort를 비웠으므로 API 기본값에 맡긴다.
        assertThat(params.outputConfig().get().effort()).isEmpty();
    }

    @Test
    void 스키마가_없고_effort도_없으면_output_config를_보내지_않는다() {
        MessageCreateParams params = newClient(propertiesWithKey()).buildParams(request(null, null));

        assertThat(params.outputConfig()).isEmpty();
        // userText가 없으면 이미지만 들어간다.
        assertThat(params.messages().get(0).content().asBlockParams()).allMatch(ContentBlockParam::isImage);
    }

    @Test
    void max_tokens는_호출부_값에_thinking_여유분을_더한_값이다() {
        ClaudeClientProperties properties = propertiesWithKey();
        properties.setOutputTokenAllowance(2500);

        MessageCreateParams params = newClient(properties).buildParams(request(null, null));

        assertThat(params.maxTokens()).isEqualTo(900L + 2500L);
    }

    @Test
    void effort_설정이_있으면_output_config_effort로_실린다() {
        ClaudeClientProperties properties = propertiesWithKey();
        properties.setEffort("Low");

        MessageCreateParams params = newClient(properties).buildParams(request(null, null));

        assertThat(params.outputConfig()).isPresent();
        assertThat(params.outputConfig().get().effort()).isPresent();
        assertThat(params.outputConfig().get().effort().get().asString()).isEqualTo("low");
        assertThat(params.outputConfig().get().format()).isEmpty();
    }

    @Test
    void 응답의_text_블록만_이어_붙이고_thinking_블록은_건너뛴다() {
        Message message = messageOf("end_turn", """
                [{"type":"thinking","thinking":"","signature":"sig"},
                 {"type":"text","text":"{\\"brand\\":"},
                 {"type":"text","text":"\\"Nike\\"}"}]
                """);

        VisionChatResponse response = newClient(propertiesWithKey()).toResponse(message, 1234L);

        assertThat(response.content()).isEqualTo("{\"brand\":\"Nike\"}");
        assertThat(response.promptTokens()).isEqualTo(1200);
        assertThat(response.completionTokens()).isEqualTo(300);
        assertThat(response.latencyMs()).isEqualTo(1234L);
    }

    @Test
    void max_tokens에_걸려_잘린_응답은_파싱_전에_실패시킨다() {
        Message message = messageOf("max_tokens", """
                [{"type":"text","text":"{\\"brand\\":"}]
                """);

        assertThatThrownBy(() -> newClient(propertiesWithKey()).toResponse(message, 1L))
                .isInstanceOf(AiApiException.class)
                .hasMessageContaining("max_tokens");
    }

    @Test
    void 거절_응답은_사유와_함께_실패시킨다() {
        Message message = messageOf("refusal", "[]",
                "{\"type\":\"refusal\",\"category\":\"other\",\"explanation\":\"policy\"}");

        assertThatThrownBy(() -> newClient(propertiesWithKey()).toResponse(message, 1L))
                .isInstanceOf(AiApiException.class)
                .hasMessageContaining("거부")
                .hasMessageContaining("policy");
    }

    @Test
    void 텍스트_블록이_하나도_없으면_실패시킨다() {
        Message message = messageOf("end_turn", "[]");

        assertThatThrownBy(() -> newClient(propertiesWithKey()).toResponse(message, 1L))
                .isInstanceOf(AiApiException.class)
                .hasMessageContaining("텍스트 블록");
    }

    @Test
    void 키가_없으면_기동은_되지만_첫_호출에서_명확히_실패한다() {
        ClaudeChatClient client = newClient(new ClaudeClientProperties());

        assertThatThrownBy(() -> client.complete(request(null, null)))
                .isInstanceOf(AiApiException.class)
                .hasMessageContaining("ANTHROPIC_API_KEY");
    }
}
