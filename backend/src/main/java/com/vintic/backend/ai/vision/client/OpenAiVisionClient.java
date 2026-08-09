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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    // 429(분당 토큰 한도)와 5xx는 잠시 뒤 다시 부르면 대개 성공하는 일시적 오류다.
    // 한 번 실패했다고 분석 전체를 실패로 떨어뜨리면 사용자는 처음부터 다시 올려야 한다.
    private static final int MAX_ATTEMPTS = 5;

    // 서버가 알려준 대기 시간에 얹는 여유. 한도 창이 흐르는 동안 다른 요청이 끼어들 수 있어
    // 알려준 시간에 딱 맞춰 다시 찌르면 또 걸린다.
    private static final long RETRY_MARGIN_MS = 500L;

    // 서버가 비정상적으로 긴 대기를 요구해도 여기서 끊는다.
    private static final long MAX_RETRY_WAIT_MS = 30_000L;

    // 429 응답 본문의 "Please try again in 5.768s" / "... in 442ms"에서 대기 시간을 읽는다.
    private static final Pattern RETRY_HINT_PATTERN =
            Pattern.compile("try again in ([0-9.]+)(ms|s)", Pattern.CASE_INSENSITIVE);

    @Value("${openai.api.key}")
    private String apiKey;

    // 재시도 간격. 테스트에서 짧게 줄일 수 있도록 설정값으로 뺐다.
    @Value("${openai.vision.retry-backoff-ms:1000}")
    private long retryBackoffMs = 1000L;

    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    public VisionChatResponse complete(VisionChatRequest request) {
        Map<String, Object> body = buildRequestBody(request);

        long startedAt = System.currentTimeMillis();
        ResponseEntity<String> response = callWithRetry(body, request);
        long latencyMs = System.currentTimeMillis() - startedAt;

        return extractResponse(response.getBody(), latencyMs);
    }

    private ResponseEntity<String> callWithRetry(Map<String, Object> body, VisionChatRequest request) {
        for (int attempt = 1; ; attempt++) {
            try {
                return callChatCompletionsApi(body, request);
            } catch (AiApiException e) {
                if (attempt >= MAX_ATTEMPTS || !isRetryable(e)) {
                    throw e;
                }
                long waitMs = retryDelayMs(e, attempt);
                log.warn("Vision 호출을 재시도합니다. attempt={}/{}, waitMs={}, reason={}",
                        attempt, MAX_ATTEMPTS, waitMs, firstLineOf(e.getMessage()));
                sleep(waitMs);
            }
        }
    }

    // 분당 한도에 걸리면 OpenAI가 얼마나 기다려야 하는지 알려준다. 그걸 무시하고 고정 백오프로
    // 다시 찌르면 아직 창이 안 풀려서 또 걸린다 - 재시도 횟수만 태우고 실패한다.
    private long retryDelayMs(AiApiException e, int attempt) {
        long backoffMs = retryBackoffMs * (1L << (attempt - 1)); // 1s, 2s, 4s, 8s
        Long serverHintMs = serverRequestedWaitMs(e);
        if (serverHintMs == null) {
            return backoffMs;
        }
        // 서버가 알려준 시간이 백오프보다 짧아도, 백오프보다 덜 기다리지는 않는다.
        return Math.min(MAX_RETRY_WAIT_MS, Math.max(backoffMs, serverHintMs + RETRY_MARGIN_MS));
    }

    private Long serverRequestedWaitMs(AiApiException e) {
        if (!(e.getCause() instanceof HttpStatusCodeException statusError)) {
            return null;
        }
        Long fromHeader = parseDuration(statusError.getResponseHeaders() == null
                ? null : statusError.getResponseHeaders().getFirst("retry-after-ms"));
        if (fromHeader != null) {
            return fromHeader;
        }
        Matcher matcher = RETRY_HINT_PATTERN.matcher(statusError.getResponseBodyAsString());
        if (!matcher.find()) {
            return null;
        }
        double value = Double.parseDouble(matcher.group(1));
        return Math.round("s".equalsIgnoreCase(matcher.group(2)) ? value * 1000 : value);
    }

    private Long parseDuration(String rawMillis) {
        if (rawMillis == null || rawMillis.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(rawMillis.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String firstLineOf(String message) {
        return compact(message);
    }

    // 오류 본문을 한 줄로 눌러 담는다. 429가 반복되면 여러 줄짜리 JSON이 로그를 덮어버린다.
    private String compact(String body) {
        if (body == null) {
            return null;
        }
        String oneLine = body.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 300 ? oneLine.substring(0, 300) + "…" : oneLine;
    }

    private boolean isRetryable(AiApiException e) {
        if (e.getCause() instanceof HttpStatusCodeException statusError) {
            // 429는 분당 한도, 5xx는 OpenAI 쪽 일시 장애. 4xx 나머지(잘못된 스키마, 인증 실패 등)는
            // 다시 불러도 같은 결과라 재시도하면 안 된다.
            return statusError.getStatusCode().value() == 429 || statusError.getStatusCode().is5xxServerError();
        }
        // 연결 실패나 타임아웃도 일시적일 수 있다.
        return e.getCause() instanceof ResourceAccessException;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiApiException("Vision 호출 재시도 대기 중 중단되었습니다.", e);
        }
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
            // 본문은 한 줄로 줄여서 남긴다 - 429가 반복되면 여러 줄짜리 JSON이 로그를 통째로 덮는다.
            log.error(
                    "OpenAI Vision API가 오류 응답을 반환했습니다. model={}, detail={}, status={}, body={}",
                    request.model(), request.detail().value(), e.getStatusCode().value(),
                    compact(e.getResponseBodyAsString())
            );
            throw new AiApiException(
                    "OpenAI Vision API 오류 (status=%d): %s".formatted(
                            e.getStatusCode().value(), compact(e.getResponseBodyAsString())),
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
