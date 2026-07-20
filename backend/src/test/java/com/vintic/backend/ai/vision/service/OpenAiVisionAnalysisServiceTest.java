package com.vintic.backend.ai.vision.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.ai.vision.dto.VisionAnalysisRequest;
import com.vintic.backend.ai.vision.dto.VisionAnalysisResult;
import com.vintic.backend.common.exception.AiApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpenAiVisionAnalysisServiceTest {

    @Mock
    private OpenAiService openAiService;

    private final ObjectMapper objectMapper = new ObjectMapper();

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
        when(openAiService.analyzeProductImages(imageUrls)).thenReturn(rawJson);

        OpenAiVisionAnalysisService sut = new OpenAiVisionAnalysisService(openAiService, objectMapper);

        VisionAnalysisResult result = sut.analyze(new VisionAnalysisRequest(imageUrls));

        assertThat(result.brand()).isEqualTo("Nike");
        assertThat(result.modelName()).isEqualTo("Air Jordan 1 Retro High OG");
        assertThat(result.color()).isEqualTo("Chicago Lost and Found");
        assertThat(result.size()).isEqualTo(270);
        assertThat(result.conditionGrade().name()).isEqualTo("B");
        assertThat(result.boxIncluded()).isTrue();

        verify(openAiService, times(1)).analyzeProductImages(imageUrls);
    }

    @Test
    void OpenAiService가_던진_예외는_그대로_전파된다() {
        List<String> imageUrls = List.of("https://example.com/a.jpg");
        when(openAiService.analyzeProductImages(anyList()))
                .thenThrow(new AiApiException("AI 분석 API 호출 중 오류가 발생했습니다."));

        OpenAiVisionAnalysisService sut = new OpenAiVisionAnalysisService(openAiService, objectMapper);

        assertThatThrownBy(() -> sut.analyze(new VisionAnalysisRequest(imageUrls)))
                .isInstanceOf(AiApiException.class);
    }

    @Test
    void 응답_형식이_잘못되면_AiApiException으로_변환한다() {
        List<String> imageUrls = List.of("https://example.com/a.jpg");
        when(openAiService.analyzeProductImages(anyList())).thenReturn("이건 JSON이 아닙니다");

        OpenAiVisionAnalysisService sut = new OpenAiVisionAnalysisService(openAiService, objectMapper);

        assertThatThrownBy(() -> sut.analyze(new VisionAnalysisRequest(imageUrls)))
                .isInstanceOf(AiApiException.class);
    }
}
