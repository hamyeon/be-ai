package com.vintic.backend.ai.vision.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.common.exception.AiApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpenAiVisionClientTest {

    private static final String SUCCESS_BODY = """
            {
              "choices": [{"finish_reason": "stop", "message": {"content": "{\\"brand\\":\\"Nike\\"}"}}],
              "usage": {"prompt_tokens": 1200, "completion_tokens": 300}
            }
            """;

    @Mock
    private RestTemplate restTemplate;

    private OpenAiVisionClient sut;

    @BeforeEach
    void setUp() {
        sut = new OpenAiVisionClient(new ObjectMapper(), restTemplate);
        ReflectionTestUtils.setField(sut, "apiKey", "test-key");
        ReflectionTestUtils.setField(sut, "retryBackoffMs", 1L); // 테스트가 실제로 기다리지 않도록
    }

    private VisionChatRequest request() {
        return new VisionChatRequest(
                "gpt-4o", "system", null, List.of("https://example.com/a.jpg"),
                VisionImageDetail.HIGH, null, 1000);
    }

    private void stubExchange(Object... responsesOrErrors) {
        var stub = when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(), eq(String.class)));
        for (Object item : responsesOrErrors) {
            if (item instanceof RuntimeException error) {
                stub = stub.thenThrow(error);
            } else {
                stub = stub.thenReturn(ResponseEntity.ok((String) item));
            }
        }
    }

    private HttpClientErrorException rateLimited() {
        return HttpClientErrorException.create(
                HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", null,
                "{\"error\":{\"code\":\"rate_limit_exceeded\"}}".getBytes(), null);
    }

    @Test
    void 정상_응답에서_본문과_토큰_사용량을_꺼낸다() {
        stubExchange(SUCCESS_BODY);

        VisionChatResponse response = sut.complete(request());

        assertThat(response.content()).contains("Nike");
        assertThat(response.promptTokens()).isEqualTo(1200);
        assertThat(response.completionTokens()).isEqualTo(300);
        assertThat(response.totalTokens()).isEqualTo(1500);
    }

    @Test
    void 분당_한도에_걸리면_잠시_뒤_다시_시도한다() {
        stubExchange(rateLimited(), rateLimited(), SUCCESS_BODY);

        VisionChatResponse response = sut.complete(request());

        assertThat(response.content()).contains("Nike");
        verify(restTemplate, times(3)).exchange(any(String.class), eq(HttpMethod.POST), any(), eq(String.class));
    }

    @Test
    void 재시도_횟수를_넘기면_예외를_던진다() {
        stubExchange(rateLimited(), rateLimited(), rateLimited(), rateLimited());

        assertThatThrownBy(() -> sut.complete(request()))
                .isInstanceOf(AiApiException.class)
                .hasMessageContaining("429");

        verify(restTemplate, times(4)).exchange(any(String.class), eq(HttpMethod.POST), any(), eq(String.class));
    }

    @Test
    void 서버_오류와_네트워크_오류도_재시도한다() {
        stubExchange(
                HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "unavailable", null, null, null),
                new ResourceAccessException("connection reset"),
                SUCCESS_BODY
        );

        assertThat(sut.complete(request()).content()).contains("Nike");
        verify(restTemplate, times(3)).exchange(any(String.class), eq(HttpMethod.POST), any(), eq(String.class));
    }

    @Test
    void 스키마_오류처럼_다시_불러도_같은_결과인_오류는_재시도하지_않는다() {
        // 400은 요청 자체가 잘못된 것이라 재시도해봐야 토큰만 쓴다
        stubExchange(HttpClientErrorException.create(
                HttpStatus.BAD_REQUEST, "Bad Request", null,
                "{\"error\":{\"message\":\"Invalid schema\"}}".getBytes(), null));

        assertThatThrownBy(() -> sut.complete(request()))
                .isInstanceOf(AiApiException.class)
                .hasMessageContaining("400");

        verify(restTemplate, times(1)).exchange(any(String.class), eq(HttpMethod.POST), any(), eq(String.class));
    }

    @Test
    void 인증_실패도_재시도하지_않는다() {
        stubExchange(HttpClientErrorException.create(
                HttpStatus.UNAUTHORIZED, "Unauthorized", null, new byte[0], null));

        assertThatThrownBy(() -> sut.complete(request())).isInstanceOf(AiApiException.class);

        verify(restTemplate, times(1)).exchange(any(String.class), eq(HttpMethod.POST), any(), eq(String.class));
    }

    @Test
    void 응답이_잘리면_파싱을_시도하지_않고_예외를_던진다() {
        stubExchange("""
                {
                  "choices": [{"finish_reason": "length", "message": {"content": "{\\"brand\\":\\"Ni"}}],
                  "usage": {"prompt_tokens": 100, "completion_tokens": 1000}
                }
                """);

        assertThatThrownBy(() -> sut.complete(request()))
                .isInstanceOf(AiApiException.class)
                .hasMessageContaining("max_tokens");

        // 잘린 응답은 다시 불러도 같으므로 재시도하지 않는다
        verify(restTemplate, times(1)).exchange(any(String.class), eq(HttpMethod.POST), any(), eq(String.class));
    }

    @Test
    void 모델이_거부하면_거부_사유를_담아_예외를_던진다() {
        stubExchange("""
                {
                  "choices": [{"finish_reason": "stop", "message": {"refusal": "I cannot help with that."}}],
                  "usage": {"prompt_tokens": 100, "completion_tokens": 10}
                }
                """);

        assertThatThrownBy(() -> sut.complete(request()))
                .isInstanceOf(AiApiException.class)
                .hasMessageContaining("거부");
    }

    @Test
    void 스키마가_있으면_strict_모드로_요청한다() {
        stubExchange(SUCCESS_BODY);
        VisionChatRequest withSchema = new VisionChatRequest(
                "gpt-4o", "system", null, List.of("https://example.com/a.jpg"), VisionImageDetail.LOW,
                new VisionChatRequest.ResponseSchema("vision_test", "{\"type\":\"object\"}"), 1000);

        sut.complete(withSchema);

        verify(restTemplate).exchange(any(String.class), eq(HttpMethod.POST), any(), eq(String.class));
        verify(restTemplate, never()).getForObject(any(String.class), any());
    }

    @Test
    void 스키마가_올바른_JSON이_아니면_호출_전에_실패한다() {
        VisionChatRequest brokenSchema = new VisionChatRequest(
                "gpt-4o", "system", null, List.of("https://example.com/a.jpg"), VisionImageDetail.LOW,
                new VisionChatRequest.ResponseSchema("vision_broken", "이건 JSON이 아닙니다"), 1000);

        assertThatThrownBy(() -> sut.complete(brokenSchema))
                .isInstanceOf(AiApiException.class)
                .hasMessageContaining("vision_broken");

        verify(restTemplate, never()).exchange(any(String.class), eq(HttpMethod.POST), any(), eq(String.class));
    }
}
