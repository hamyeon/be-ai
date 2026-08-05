package com.vintic.backend.ai.vision.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.common.exception.AiApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// OpenAI Chat Completions API(/v1/chat/completions)를 Vision 용도로 호출하는 클라이언트.
// 임베딩 클라이언트(OpenAiEmbeddingClient)와 엔드포인트/응답 구조가 달라 분리돼 있다.
//
// 이 클래스는 "한 번 호출하고 본문을 꺼내는 것"까지만 한다. 몇 단계로 나눠 부를지, 어떤 스키마를 쓸지는
// 호출하는 쪽(VisionAnalysisService 구현체)이 정한다.
@Service
@RequiredArgsConstructor
@Slf4j
public class OpenAiVisionClient {

    private static final String URL = "https://api.openai.com/v1/chat/completions";

    @Value("${openai.api.key}")
    private String apiKey;

    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    public VisionChatResponse complete(VisionChatRequest request) {
        Map<String, Object> body = buildRequestBody(request);

        long startedAt = System.currentTimeMillis();
        ResponseEntity<String> response = callChatCompletionsApi(body, request);
        long latencyMs = System.currentTimeMillis() - startedAt;

        return extractResponse(response.getBody(), latencyMs);
    }

    private Map<String, Object> buildRequestBody(VisionChatRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.model());
        body.put("messages", List.of(
                Map.of("role", "system", "content", request.systemPrompt()),
                Map.of("role", "user", "content", buildUserContent(request))
        ));
        body.put("response_format", buildResponseFormat(request.responseSchema()));
        body.put("max_tokens", request.maxOutputTokens());
        return body;
    }

    private List<Map<String, Object>> buildUserContent(VisionChatRequest request) {
        List<Map<String, Object>> content = new ArrayList<>();

        // 이전 단계 결과가 있으면 이미지보다 먼저 넣어, 모델이 이미지를 보기 전에 맥락을 잡게 한다.
        if (request.userText() != null && !request.userText().isBlank()) {
            content.add(Map.of("type", "text", "text", request.userText()));
        }
        for (String imageUrl : request.imageUrls()) {
            content.add(Map.of(
                    "type", "image_url",
                    "image_url", Map.of("url", imageUrl, "detail", request.detail().value())
            ));
        }
        return content;
    }

    private Map<String, Object> buildResponseFormat(VisionChatRequest.ResponseSchema responseSchema) {
        if (responseSchema == null) {
            return Map.of("type", "json_object");
        }
        // Structured Outputs. strict=true면 OpenAI가 스키마를 강제하므로 필드 누락/오타 응답 자체가 나오지 않는다.
        return Map.of(
                "type", "json_schema",
                "json_schema", Map.of(
                        "name", responseSchema.name(),
                        "strict", true,
                        "schema", readSchema(responseSchema)
                )
        );
    }

    private JsonNode readSchema(VisionChatRequest.ResponseSchema responseSchema) {
        try {
            return objectMapper.readTree(responseSchema.schemaJson());
        } catch (Exception e) {
            throw new AiApiException("Vision 응답 스키마가 올바른 JSON이 아닙니다: " + responseSchema.name(), e);
        }
    }

    private ResponseEntity<String> callChatCompletionsApi(Map<String, Object> body, VisionChatRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(body, headers);

        try {
            return restTemplate.exchange(URL, HttpMethod.POST, requestEntity, String.class);
        } catch (HttpStatusCodeException e) {
            // 주의: headers/requestEntity는 Authorization(API 키)을 담고 있으므로 절대 로그로 남기지 않는다.
            log.error(
                    "OpenAI Vision API가 오류 응답을 반환했습니다. model={}, detail={}, status={}, body={}",
                    request.model(), request.detail().value(), e.getStatusCode().value(), e.getResponseBodyAsString()
            );
            throw new AiApiException(
                    "OpenAI Vision API 오류 (status=%d): %s".formatted(
                            e.getStatusCode().value(), e.getResponseBodyAsString()),
                    e
            );
        } catch (ResourceAccessException e) {
            log.error(
                    "OpenAI Vision API 호출 중 네트워크 오류가 발생했습니다. exceptionType={}, message={}",
                    e.getClass().getSimpleName(), e.getMessage()
            );
            throw new AiApiException("OpenAI Vision API 호출 중 네트워크 오류가 발생했습니다: " + e.getMessage(), e);
        }
    }

    private VisionChatResponse extractResponse(String responseBody, long latencyMs) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode choice = root.path("choices").get(0);

            // Structured Outputs를 쓰더라도 max_tokens에 걸려 잘리면 불완전한 JSON이 온다.
            // 이 경우 파싱 단계에서 애매하게 실패하므로 여기서 먼저 끊는다.
            String finishReason = choice.path("finish_reason").asText();
            if ("length".equals(finishReason)) {
                throw new AiApiException("Vision 응답이 max_tokens 한도에 걸려 잘렸습니다.");
            }

            JsonNode refusal = choice.path("message").path("refusal");
            if (!refusal.isNull() && !refusal.isMissingNode() && !refusal.asText().isBlank()) {
                throw new AiApiException("Vision 모델이 응답을 거부했습니다: " + refusal.asText());
            }

            JsonNode usage = root.path("usage");
            return new VisionChatResponse(
                    choice.path("message").path("content").asText(),
                    usage.path("prompt_tokens").asInt(),
                    usage.path("completion_tokens").asInt(),
                    latencyMs
            );
        } catch (AiApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("OpenAI Vision 응답에서 분석 결과를 추출하지 못했습니다. exceptionType={}, message={}",
                    e.getClass().getSimpleName(), e.getMessage());
            throw new AiApiException("OpenAI Vision 응답에서 분석 결과를 추출할 수 없습니다: " + e.getMessage(), e);
        }
    }
}
