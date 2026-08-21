package com.vintic.backend.ai.search.embedding;

import com.vintic.backend.ai.observability.service.AiCallLogger;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.common.exception.AiApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpenAiEmbeddingClientTest {

    @Mock
    private RestTemplate restTemplate;

    // 호출 기록은 AiCallLoggerTest가 검증한다. 여기서는 추출 로직만 본다.
    @Mock
    private AiCallLogger aiCallLogger;

    private OpenAiEmbeddingClient newClient() {
        OpenAiEmbeddingClient client = new OpenAiEmbeddingClient(new ObjectMapper(), restTemplate, aiCallLogger);
        ReflectionTestUtils.setField(client, "apiKey", "test-key");
        return client;
    }

    @Test
    void 정상_응답이면_벡터를_추출한다() {
        String responseBody = """
                {"data":[{"embedding":[0.1,0.2,0.3]}]}
                """;
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(responseBody));

        float[] vector = newClient().embed("테스트 텍스트");

        assertThat(vector).containsExactly(0.1f, 0.2f, 0.3f);
    }

    @Test
    void OpenAI가_HTTP_오류를_반환하면_상태코드와_응답본문을_포함해_원인을_보존한다() {
        HttpClientErrorException httpError = HttpClientErrorException.create(
                HttpStatus.UNAUTHORIZED,
                "Unauthorized",
                null,
                "{\"error\":{\"message\":\"Invalid API key\"}}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8
        );
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenThrow(httpError);

        assertThatThrownBy(() -> newClient().embed("테스트 텍스트"))
                .isInstanceOf(AiApiException.class)
                .hasCause(httpError)
                .hasMessageContaining("401")
                .hasMessageContaining("Invalid API key");
    }

    @Test
    void 네트워크_오류면_원인을_보존한_채로_구분된_예외를_던진다() {
        ResourceAccessException networkError = new ResourceAccessException("Connection refused");
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenThrow(networkError);

        assertThatThrownBy(() -> newClient().embed("테스트 텍스트"))
                .isInstanceOf(AiApiException.class)
                .hasCause(networkError)
                .hasMessageContaining("네트워크");
    }

    @Test
    void 응답_형식이_잘못되면_원인을_보존한_채로_예외를_던진다() {
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("이건 JSON이 아닙니다"));

        assertThatThrownBy(() -> newClient().embed("테스트 텍스트"))
                .isInstanceOf(AiApiException.class)
                .hasCauseInstanceOf(Exception.class);
    }
}
