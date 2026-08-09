package com.vintic.backend.ai.vision.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.ai.prompt.PromptTemplateLoader;
import com.vintic.backend.ai.vision.client.OpenAiVisionClient;
import com.vintic.backend.ai.vision.client.VisionChatRequest;
import com.vintic.backend.ai.vision.client.VisionChatResponse;
import com.vintic.backend.ai.vision.dto.VisionAnalysisRequest;
import com.vintic.backend.ai.vision.dto.VisionAnalysisResult;
import com.vintic.backend.common.exception.AiApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpenAiVisionAnalysisServiceTest {

    @Mock
    private OpenAiVisionClient visionClient;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PromptTemplateLoader promptTemplateLoader = new PromptTemplateLoader();

    private OpenAiVisionAnalysisService newService() {
        return new OpenAiVisionAnalysisService(visionClient, objectMapper, promptTemplateLoader);
    }

    private VisionChatResponse responseOf(String content) {
        return new VisionChatResponse(content, 100, 50, 1200L);
    }

    @Test
    void 정상_JSON_응답을_VisionAnalysisResult로_변환한다() {
        String rawJson = """
                {
                  "brand": "Nike",
                  "modelName": "Air Jordan 1 Retro High OG",
                  "color": "Chicago Lost and Found",
                  "size": 270,
                  "conditionDescription": "사용감이 거의 없습니다.",
                  "conditionGrade": "B",
                  "boxIncluded": true,
                  "confidence": 0.82,
                  "needsUserConfirmation": true,
                  "warnings": ["Size is not visible in the image."],
                  "candidates": []
                }
                """;
        List<String> imageUrls = List.of("https://example.com/a.jpg");
        when(visionClient.complete(any())).thenReturn(responseOf(rawJson));

        VisionAnalysisResult result = newService().analyze(new VisionAnalysisRequest(imageUrls));

        assertThat(result.brand()).isEqualTo("Nike");
        assertThat(result.modelName()).isEqualTo("Air Jordan 1 Retro High OG");
        assertThat(result.color()).isEqualTo("Chicago Lost and Found");
        assertThat(result.size()).isEqualTo(270);
        assertThat(result.conditionGrade().name()).isEqualTo("B");
        assertThat(result.boxIncluded()).isTrue();

        verify(visionClient, times(1)).complete(any());
    }

    @Test
    void 로드된_시스템_프롬프트와_이미지를_그대로_전달한다() {
        List<String> imageUrls = List.of("https://example.com/a.jpg");
        when(visionClient.complete(any())).thenReturn(responseOf("""
                {"brand":"Nike","modelName":"m","color":"c","size":270,"conditionDescription":"d",
                 "conditionGrade":"B","boxIncluded":true,"confidence":0.5,"needsUserConfirmation":false,
                 "warnings":[],"candidates":[]}
                """));

        newService().analyze(new VisionAnalysisRequest(imageUrls));

        ArgumentCaptor<VisionChatRequest> captor = ArgumentCaptor.forClass(VisionChatRequest.class);
        verify(visionClient).complete(captor.capture());
        assertThat(captor.getValue().systemPrompt()).contains("used sneaker product analysis expert");
        assertThat(captor.getValue().imageUrls()).isEqualTo(imageUrls);
        // v1은 스키마를 고정하지 않는 기준선 구현이다
        assertThat(captor.getValue().responseSchema()).isNull();
    }

    @Test
    void 클라이언트가_던진_예외는_그대로_전파된다() {
        when(visionClient.complete(any()))
                .thenThrow(new AiApiException("AI 분석 API 호출 중 오류가 발생했습니다."));

        assertThatThrownBy(() -> newService().analyze(new VisionAnalysisRequest(List.of("https://example.com/a.jpg"))))
                .isInstanceOf(AiApiException.class);
    }

    @Test
    void 응답_형식이_잘못되면_AiApiException으로_변환한다() {
        when(visionClient.complete(any())).thenReturn(responseOf("이건 JSON이 아닙니다"));

        assertThatThrownBy(() -> newService().analyze(new VisionAnalysisRequest(List.of("https://example.com/a.jpg"))))
                .isInstanceOf(AiApiException.class);
    }
}
