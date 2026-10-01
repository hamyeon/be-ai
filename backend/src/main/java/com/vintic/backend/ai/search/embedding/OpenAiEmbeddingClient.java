package com.vintic.backend.ai.search.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.ai.observability.domain.AiCallFailureType;
import com.vintic.backend.ai.observability.domain.AiCallLog;
import com.vintic.backend.ai.observability.domain.AiCallType;
import com.vintic.backend.ai.observability.service.AiCallLogger;
import com.vintic.backend.ai.observability.service.AiCallRequestSummary;
import com.vintic.backend.common.exception.AiApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

// OpenAI Embeddings API(/v1/embeddings) 호출 클라이언트. Vision의 OpenAiService와 별개 엔드포인트라 분리했다.
@Service
@RequiredArgsConstructor
@Slf4j
public class OpenAiEmbeddingClient implements EmbeddingClient {

    private static final String EMBEDDING_MODEL = "text-embedding-3-small";
    private static final String URL = "https://api.openai.com/v1/embeddings";

    @Value("${openai.api.key}")
    private String apiKey;

    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
    private final AiCallLogger aiCallLogger;

    @Override
    public float[] embed(String text) {
        String requestSummary = AiCallRequestSummary.ofEmbedding(EMBEDDING_MODEL, text, objectMapper);
        long startedAt = System.currentTimeMillis();

        ResponseEntity<String> response;
        try {
            response = callEmbeddingApi(text);
        } catch (RuntimeException e) {
            record(requestSummary, System.currentTimeMillis() - startedAt, 0,
                    builder -> builder.failure(AiCallFailureType.API_ERROR, e.getMessage()));
            throw e;
        }

        long latencyMs = System.currentTimeMillis() - startedAt;
        try {
            float[] vector = extractEmbedding(response.getBody());
            record(requestSummary, latencyMs, promptTokensOf(response.getBody()), builder -> builder);
            return vector;
        } catch (RuntimeException e) {
            record(requestSummary, latencyMs, 0,
                    builder -> builder.failure(AiCallFailureType.PARSE_ERROR, e.getMessage()));
            throw e;
        }
    }

    private void record(String requestSummary, long latencyMs, int promptTokens,
                        UnaryOperator<AiCallLog.Builder> customizer) {
        AiCallLog.Builder builder = AiCallLog.builder(AiCallType.EMBEDDING, EMBEDDING_MODEL)
                .requestSummary(requestSummary)
                .latencyMs(latencyMs)
                // 임베딩 응답에는 completion 토큰이 없다. 입력 토큰만 과금된다.
                .tokens(promptTokens, 0);
        // 벡터 1536개를 그대로 남기면 행이 커지기만 하고 되짚을 값이 없다. 벡터는 product_vectors에 있다.
        aiCallLogger.record(customizer.apply(builder).build());
    }

    private int promptTokensOf(String responseBody) {
        try {
            return objectMapper.readTree(responseBody).path("usage").path("prompt_tokens").asInt(0);
        } catch (Exception e) {
            return 0;
        }
    }

    private ResponseEntity<String> callEmbeddingApi(String text) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        Map<String, Object> body = new HashMap<>();
        body.put("model", EMBEDDING_MODEL);
        body.put("input", text);

        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(body, headers);

        try {
            return restTemplate.exchange(URL, HttpMethod.POST, requestEntity, String.class);
        } catch (HttpStatusCodeException e) {
            // OpenAI가 명확한 HTTP 오류로 응답한 경우(4xx/5xx) - 상태코드와 응답 본문을 확인할 수 있다.
            // 주의: headers/requestEntity는 Authorization(API 키)을 담고 있으므로 절대 로그로 남기지 않는다.
            log.error(
                    "OpenAI 임베딩 API가 오류 응답을 반환했습니다. status={}, body={}, exceptionType={}, message={}",
                    e.getStatusCode().value(),
                    e.getResponseBodyAsString(),
                    e.getClass().getSimpleName(),
                    e.getMessage()
            );
            throw new AiApiException(
                    "OpenAI 임베딩 API 오류 (status=%d): %s".formatted(e.getStatusCode().value(), e.getResponseBodyAsString()),
                    e
            );
        } catch (ResourceAccessException e) {
            // 서버 응답 자체를 못 받은 네트워크 오류(연결 실패, 타임아웃 등) - 상태코드/본문이 존재하지 않는다.
            log.error(
                    "OpenAI 임베딩 API 호출 중 네트워크 오류가 발생했습니다. exceptionType={}, message={}",
                    e.getClass().getSimpleName(), e.getMessage()
            );
            throw new AiApiException("OpenAI 임베딩 API 호출 중 네트워크 오류가 발생했습니다: " + e.getMessage(), e);
        }
    }

    private float[] extractEmbedding(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode embeddingNode = root.path("data").get(0).path("embedding");

            float[] vector = new float[embeddingNode.size()];
            for (int i = 0; i < embeddingNode.size(); i++) {
                vector[i] = (float) embeddingNode.get(i).asDouble();
            }
            return vector;
        } catch (Exception e) {
            log.error(
                    "OpenAI 임베딩 응답에서 벡터를 추출하지 못했습니다. exceptionType={}, message={}",
                    e.getClass().getSimpleName(), e.getMessage()
            );
            throw new AiApiException("OpenAI 임베딩 응답에서 벡터를 추출할 수 없습니다: " + e.getMessage(), e);
        }
    }
}
