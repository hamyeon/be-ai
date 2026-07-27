package com.vintic.backend.ai.search.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.common.exception.AiApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

// OpenAI Embeddings API(/v1/embeddings) 호출 클라이언트. Vision의 OpenAiService와 별개 엔드포인트라 분리했다.
@Service
@RequiredArgsConstructor
public class OpenAiEmbeddingClient implements EmbeddingClient {

    private static final String EMBEDDING_MODEL = "text-embedding-3-small";
    private static final String URL = "https://api.openai.com/v1/embeddings";

    @Value("${openai.api.key}")
    private String apiKey;

    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate = new RestTemplate();

    @Override
    public float[] embed(String text) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            Map<String, Object> body = new HashMap<>();
            body.put("model", EMBEDDING_MODEL);
            body.put("input", text);

            HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(body, headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    URL, HttpMethod.POST, requestEntity, String.class
            );

            return extractEmbedding(response.getBody());
        } catch (AiApiException e) {
            throw e;
        } catch (Exception e) {
            throw new AiApiException("OpenAI 임베딩 생성 중 오류가 발생했습니다.");
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
            throw new AiApiException("OpenAI 임베딩 응답에서 벡터를 추출할 수 없습니다.");
        }
    }
}
