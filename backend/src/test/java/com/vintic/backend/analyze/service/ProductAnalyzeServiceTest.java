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
import com.vintic.backend.common.exception.AiApiException;
import com.vintic.backend.common.exception.InvalidImageException;
import com.vintic.backend.common.exception.S3UploadException;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
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

    @Mock
    private AnalysisFailureRecorder failureRecorder;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void 정상_요청이면_기존_API_응답_형식을_유지한다() {
        when(sessionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ProductAnalyzeService sut = new ProductAnalyzeService(
                s3UploaderService, visionAnalysisService, sessionRepository, failureRecorder, objectMapper
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

        verify(failureRecorder, never()).recordImageUploadFailure(anyLong(), anyString());
        verify(failureRecorder, never()).recordVisionFailure(anyLong(), anyString());
    }

    @Test
    void 이미지가_비어있으면_InvalidImageException을_던진다() {
        ProductAnalyzeService sut = new ProductAnalyzeService(
                s3UploaderService, visionAnalysisService, sessionRepository, failureRecorder, objectMapper
        );

        assertThatThrownBy(() -> sut.processImageAndAnalyze(List.of()))
                .isInstanceOf(InvalidImageException.class);
    }

    @Test
    void S3_업로드가_실패하면_실패_기록을_남기고_예외를_그대로_던진다() {
        when(sessionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ProductAnalyzeService sut = new ProductAnalyzeService(
                s3UploaderService, visionAnalysisService, sessionRepository, failureRecorder, objectMapper
        );

        MultipartFile image = new MockMultipartFile("images", "shoe.jpg", "image/jpeg", new byte[]{1, 2, 3});
        when(s3UploaderService.uploadImages(List.of(image)))
                .thenThrow(new S3UploadException("S3 이미지 업로드 중 문제가 발생했습니다."));

        assertThatThrownBy(() -> sut.processImageAndAnalyze(List.of(image)))
                .isInstanceOf(S3UploadException.class);

        verify(failureRecorder).recordImageUploadFailure(any(), anyString());
        verify(visionAnalysisService, never()).analyze(any());
    }

    @Test
    void Vision_분석이_실패하면_실패_기록을_남기고_예외를_그대로_던진다() {
        when(sessionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ProductAnalyzeService sut = new ProductAnalyzeService(
                s3UploaderService, visionAnalysisService, sessionRepository, failureRecorder, objectMapper
        );

        MultipartFile image = new MockMultipartFile("images", "shoe.jpg", "image/jpeg", new byte[]{1, 2, 3});
        List<String> uploadedUrls = List.of("https://bucket.s3.amazonaws.com/shoe.jpg");
        when(s3UploaderService.uploadImages(List.of(image))).thenReturn(uploadedUrls);
        when(visionAnalysisService.analyze(any()))
                .thenThrow(new AiApiException("AI 분석 API 호출 중 오류가 발생했습니다."));

        assertThatThrownBy(() -> sut.processImageAndAnalyze(List.of(image)))
                .isInstanceOf(AiApiException.class);

        verify(failureRecorder).recordVisionFailure(any(), anyString());
        verify(failureRecorder, never()).recordImageUploadFailure(anyLong(), anyString());
    }

    @Test
    void 업로드_실패_기록_저장_중_추가_오류가_나도_원래_S3_예외가_그대로_전파된다() {
        when(sessionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        doThrow(new RuntimeException("실패 기록 저장 중 DB 오류"))
                .when(failureRecorder).recordImageUploadFailure(any(), anyString());

        ProductAnalyzeService sut = new ProductAnalyzeService(
                s3UploaderService, visionAnalysisService, sessionRepository, failureRecorder, objectMapper
        );

        MultipartFile image = new MockMultipartFile("images", "shoe.jpg", "image/jpeg", new byte[]{1, 2, 3});
        when(s3UploaderService.uploadImages(List.of(image)))
                .thenThrow(new S3UploadException("원래 S3 실패"));

        assertThatThrownBy(() -> sut.processImageAndAnalyze(List.of(image)))
                .isInstanceOf(S3UploadException.class)
                .hasMessage("원래 S3 실패");
    }

    @Test
    void Vision_실패_기록_저장_중_추가_오류가_나도_원래_Vision_예외가_그대로_전파된다() {
        when(sessionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        doThrow(new RuntimeException("실패 기록 저장 중 DB 오류"))
                .when(failureRecorder).recordVisionFailure(any(), anyString());

        ProductAnalyzeService sut = new ProductAnalyzeService(
                s3UploaderService, visionAnalysisService, sessionRepository, failureRecorder, objectMapper
        );

        MultipartFile image = new MockMultipartFile("images", "shoe.jpg", "image/jpeg", new byte[]{1, 2, 3});
        List<String> uploadedUrls = List.of("https://bucket.s3.amazonaws.com/shoe.jpg");
        when(s3UploaderService.uploadImages(List.of(image))).thenReturn(uploadedUrls);
        when(visionAnalysisService.analyze(any()))
                .thenThrow(new AiApiException("원래 Vision 실패"));

        assertThatThrownBy(() -> sut.processImageAndAnalyze(List.of(image)))
                .isInstanceOf(AiApiException.class)
                .hasMessage("원래 Vision 실패");
    }
}
