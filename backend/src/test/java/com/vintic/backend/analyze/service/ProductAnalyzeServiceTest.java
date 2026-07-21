package com.vintic.backend.analyze.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.ai.vision.dto.ConditionGrade;
import com.vintic.backend.ai.vision.dto.VisionAnalysisRequest;
import com.vintic.backend.ai.vision.dto.VisionAnalysisResult;
import com.vintic.backend.ai.vision.service.VisionAnalysisService;
import com.vintic.backend.analyze.domain.AnalysisStatus;
import com.vintic.backend.analyze.domain.ProductAnalysisSession;
import com.vintic.backend.analyze.domain.ProductAnalysisSessionRepository;
import com.vintic.backend.analyze.dto.AnalyzeResponse;
import com.vintic.backend.common.exception.InvalidImageException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductAnalyzeServiceTest {

    @Mock
    private S3UploaderService s3UploaderService;

    @Mock
    private VisionAnalysisService visionAnalysisService;

    @Mock
    private ProductAnalysisSessionRepository sessionRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void 정상_요청이면_기존_API_응답_형식을_유지한다() {
        when(sessionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ProductAnalyzeService sut = new ProductAnalyzeService(
                s3UploaderService, visionAnalysisService, sessionRepository, objectMapper
        );

        MultipartFile image = new MockMultipartFile("images", "shoe.jpg", "image/jpeg", new byte[]{1, 2, 3});
        List<String> uploadedUrls = List.of("https://bucket.s3.amazonaws.com/shoe.jpg");
        when(s3UploaderService.uploadImages(List.of(image))).thenReturn(uploadedUrls);

        VisionAnalysisResult visionResult = new VisionAnalysisResult(
                "Nike",
                "Air Jordan 1 Retro High OG",
                "Chicago Lost and Found",
                270,
                "사용감이 거의 없습니다.",
                ConditionGrade.B,
                true,
                0.82,
                false,
                List.of(),
                List.of()
        );
        when(visionAnalysisService.analyze(new VisionAnalysisRequest(uploadedUrls))).thenReturn(visionResult);

        AnalyzeResponse response = sut.processImageAndAnalyze(List.of(image));

        assertThat(response.imageUrls()).isEqualTo(uploadedUrls);
        assertThat(response.brand()).isEqualTo("Nike");
        assertThat(response.modelName()).isEqualTo("Air Jordan 1 Retro High OG");
        assertThat(response.color()).isEqualTo("Chicago Lost and Found");
        assertThat(response.size()).isEqualTo(270);
        assertThat(response.conditionDescription()).isEqualTo("사용감이 거의 없습니다.");
        assertThat(response.conditionGrade()).isEqualTo("B");

        ArgumentCaptor<VisionAnalysisRequest> requestCaptor = ArgumentCaptor.forClass(VisionAnalysisRequest.class);
        verify(visionAnalysisService).analyze(requestCaptor.capture());
        assertThat(requestCaptor.getValue().imageUrls()).isEqualTo(uploadedUrls);

        ArgumentCaptor<ProductAnalysisSession> sessionCaptor = ArgumentCaptor.forClass(ProductAnalysisSession.class);
        verify(sessionRepository, atLeastOnce()).save(sessionCaptor.capture());
        ProductAnalysisSession finalSession = sessionCaptor.getValue();
        assertThat(finalSession.getStatus()).isEqualTo(AnalysisStatus.AWAITING_USER_CONFIRMATION);
        assertThat(response.analysisId()).isEqualTo(finalSession.getId());
    }

    @Test
    void 이미지가_비어있으면_InvalidImageException을_던진다() {
        ProductAnalyzeService sut = new ProductAnalyzeService(
                s3UploaderService, visionAnalysisService, sessionRepository, objectMapper
        );

        assertThatThrownBy(() -> sut.processImageAndAnalyze(List.of()))
                .isInstanceOf(InvalidImageException.class);
    }
}
