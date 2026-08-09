package com.vintic.backend.analyze.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.ai.vision.dto.ConditionGrade;
import com.vintic.backend.ai.vision.dto.VisionAnalysisResult;
import com.vintic.backend.ai.vision.dto.VisionDefect;
import com.vintic.backend.analyze.domain.AnalysisFailureStage;
import com.vintic.backend.analyze.domain.AnalysisStatus;
import com.vintic.backend.analyze.domain.ProductAnalysisSession;
import com.vintic.backend.analyze.domain.ProductAnalysisSessionRepository;
import com.vintic.backend.analyze.dto.AnalysisStatusResponse;
import com.vintic.backend.analyze.dto.AnalyzeAcceptedResponse;
import com.vintic.backend.analyze.queue.AnalysisTaskMessage;
import com.vintic.backend.analyze.queue.AnalysisTaskProducer;
import com.vintic.backend.common.exception.AnalysisQueueException;
import com.vintic.backend.common.exception.AnalysisSessionNotFoundException;
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
import java.util.Optional;

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
    private ProductAnalysisSessionRepository sessionRepository;

    @Mock
    private AnalysisFailureRecorder failureRecorder;

    @Mock
    private AnalysisTaskProducer analysisTaskProducer;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ProductAnalyzeService newService() {
        return new ProductAnalyzeService(
                s3UploaderService, sessionRepository, failureRecorder, analysisTaskProducer, objectMapper
        );
    }

    @Test
    void 정상_요청이면_QUEUED_상태로_202_응답을_반환한다() {
        when(sessionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        MultipartFile image = new MockMultipartFile("images", "shoe.jpg", "image/jpeg", new byte[]{1, 2, 3});
        List<String> uploadedUrls = List.of("https://bucket.s3.amazonaws.com/shoe.jpg");
        when(s3UploaderService.uploadImages(List.of(image))).thenReturn(uploadedUrls);

        AnalyzeAcceptedResponse response = newService().submitForAnalysis(List.of(image));

        assertThat(response.status()).isEqualTo("QUEUED");

        ArgumentCaptor<AnalysisTaskMessage> messageCaptor = ArgumentCaptor.forClass(AnalysisTaskMessage.class);
        verify(analysisTaskProducer).enqueue(messageCaptor.capture());
        assertThat(messageCaptor.getValue().analysisId()).isEqualTo(response.analysisId());
        assertThat(messageCaptor.getValue().imageUrls()).isEqualTo(uploadedUrls);

        ArgumentCaptor<ProductAnalysisSession> sessionCaptor = ArgumentCaptor.forClass(ProductAnalysisSession.class);
        verify(sessionRepository, atLeastOnce()).save(sessionCaptor.capture());
        assertThat(sessionCaptor.getValue().getStatus()).isEqualTo(AnalysisStatus.QUEUED);

        verify(failureRecorder, never()).recordImageUploadFailure(anyLong(), anyString());
        verify(failureRecorder, never()).recordQueueingFailure(anyLong(), anyString());
    }

    @Test
    void 이미지가_비어있으면_InvalidImageException을_던진다() {
        assertThatThrownBy(() -> newService().submitForAnalysis(List.of()))
                .isInstanceOf(InvalidImageException.class);
    }

    @Test
    void S3_업로드가_실패하면_실패_기록을_남기고_예외를_그대로_던진다() {
        when(sessionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        MultipartFile image = new MockMultipartFile("images", "shoe.jpg", "image/jpeg", new byte[]{1, 2, 3});
        when(s3UploaderService.uploadImages(List.of(image)))
                .thenThrow(new S3UploadException("S3 이미지 업로드 중 문제가 발생했습니다."));

        assertThatThrownBy(() -> newService().submitForAnalysis(List.of(image)))
                .isInstanceOf(S3UploadException.class);

        verify(failureRecorder).recordImageUploadFailure(any(), anyString());
        verify(analysisTaskProducer, never()).enqueue(any());
    }

    @Test
    void 큐_적재가_실패하면_실패_기록을_남기고_예외를_그대로_던진다() {
        when(sessionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        MultipartFile image = new MockMultipartFile("images", "shoe.jpg", "image/jpeg", new byte[]{1, 2, 3});
        List<String> uploadedUrls = List.of("https://bucket.s3.amazonaws.com/shoe.jpg");
        when(s3UploaderService.uploadImages(List.of(image))).thenReturn(uploadedUrls);
        doThrow(new AnalysisQueueException("Redis 연결 실패"))
                .when(analysisTaskProducer).enqueue(any());

        assertThatThrownBy(() -> newService().submitForAnalysis(List.of(image)))
                .isInstanceOf(AnalysisQueueException.class);

        verify(failureRecorder).recordQueueingFailure(any(), anyString());
    }

    @Test
    void 업로드_실패_기록_저장_중_추가_오류가_나도_원래_S3_예외가_그대로_전파된다() {
        when(sessionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        doThrow(new RuntimeException("실패 기록 저장 중 DB 오류"))
                .when(failureRecorder).recordImageUploadFailure(any(), anyString());

        MultipartFile image = new MockMultipartFile("images", "shoe.jpg", "image/jpeg", new byte[]{1, 2, 3});
        when(s3UploaderService.uploadImages(List.of(image)))
                .thenThrow(new S3UploadException("원래 S3 실패"));

        assertThatThrownBy(() -> newService().submitForAnalysis(List.of(image)))
                .isInstanceOf(S3UploadException.class)
                .hasMessage("원래 S3 실패");
    }

    @Test
    void 큐_적재_실패_기록_저장_중_추가_오류가_나도_원래_예외가_그대로_전파된다() {
        when(sessionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        doThrow(new RuntimeException("실패 기록 저장 중 DB 오류"))
                .when(failureRecorder).recordQueueingFailure(any(), anyString());

        MultipartFile image = new MockMultipartFile("images", "shoe.jpg", "image/jpeg", new byte[]{1, 2, 3});
        List<String> uploadedUrls = List.of("https://bucket.s3.amazonaws.com/shoe.jpg");
        when(s3UploaderService.uploadImages(List.of(image))).thenReturn(uploadedUrls);
        doThrow(new AnalysisQueueException("원래 Queue 실패"))
                .when(analysisTaskProducer).enqueue(any());

        assertThatThrownBy(() -> newService().submitForAnalysis(List.of(image)))
                .isInstanceOf(AnalysisQueueException.class)
                .hasMessage("원래 Queue 실패");
    }

    @Test
    void 세션이_없으면_상태조회에서_AnalysisSessionNotFoundException을_던진다() {
        when(sessionRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> newService().getStatus(1L))
                .isInstanceOf(AnalysisSessionNotFoundException.class);
    }

    @Test
    void QUEUED_상태면_Vision_필드가_비어있는_상태로_조회된다() {
        ProductAnalysisSession session = ProductAnalysisSession.create();
        session.markImageUploaded(List.of("https://bucket.s3.amazonaws.com/shoe.jpg"));
        session.markQueued();
        when(sessionRepository.findById(1L)).thenReturn(Optional.of(session));

        AnalysisStatusResponse response = newService().getStatus(1L);

        assertThat(response.status()).isEqualTo("QUEUED");
        assertThat(response.brand()).isNull();
        assertThat(response.failureStage()).isNull();
        // 아직 분석 전이어도 리스트 필드는 null이 아니라 빈 배열로 나가야 프론트가 분기를 안 해도 된다
        assertThat(response.warnings()).isEmpty();
        assertThat(response.defects()).isEmpty();
        assertThat(response.candidates()).isEmpty();
        assertThat(response.needsUserConfirmation()).isNull();
    }

    @Test
    void Vision_완료_상태면_저장된_결과를_읽어_응답에_채운다() throws Exception {
        ProductAnalysisSession session = ProductAnalysisSession.create();
        session.markImageUploaded(List.of("https://bucket.s3.amazonaws.com/shoe.jpg"));
        session.markQueued();
        session.startVisionProcessing();

        VisionAnalysisResult visionResult = new VisionAnalysisResult(
                "Nike", "Air Jordan 1 Retro High OG", "Chicago Lost and Found", 270,
                "사용감이 거의 없습니다.", ConditionGrade.B, true, 0.82, false, List.of(), List.of(), List.of(), List.of()
        );
        session.completeVision(objectMapper.writeValueAsString(visionResult));
        when(sessionRepository.findById(1L)).thenReturn(Optional.of(session));

        AnalysisStatusResponse response = newService().getStatus(1L);

        assertThat(response.status()).isEqualTo("AWAITING_USER_CONFIRMATION");
        assertThat(response.brand()).isEqualTo("Nike");
        assertThat(response.modelName()).isEqualTo("Air Jordan 1 Retro High OG");
        assertThat(response.conditionGrade()).isEqualTo("B");
        assertThat(response.boxIncluded()).isTrue();
        assertThat(response.confidence()).isEqualTo(0.82);
        assertThat(response.needsUserConfirmation()).isFalse();
    }

    @Test
    void 근거가_없어_비워진_필드는_사유와_함께_조회된다() throws Exception {
        ProductAnalysisSession session = ProductAnalysisSession.create();
        session.markImageUploaded(List.of("https://bucket.s3.amazonaws.com/shoe.jpg"));
        session.markQueued();
        session.startVisionProcessing();

        // 근거 검증기가 사이즈를 비우고 사유를 남긴 상태
        VisionAnalysisResult visionResult = new VisionAnalysisResult(
                "Nike", "Air Force 1", "White", null,
                "앞코 주름이 보입니다.", ConditionGrade.B, null, 0.6, true,
                List.of("사이즈 표기를 읽어낸 근거가 없어 값을 비웠습니다. 라벨이나 밑창 사진을 추가해 주세요."),
                List.of(), List.of(new VisionDefect("crease", "toe_box", "moderate", "앞코에 주름이 있습니다.")),
                List.of()
        );
        session.completeVision(objectMapper.writeValueAsString(visionResult));
        when(sessionRepository.findById(1L)).thenReturn(Optional.of(session));

        AnalysisStatusResponse response = newService().getStatus(1L);

        // 사이즈가 왜 비었는지를 프론트가 알 수 있어야 사용자에게 추가 사진을 요청할 수 있다
        assertThat(response.size()).isNull();
        assertThat(response.needsUserConfirmation()).isTrue();
        assertThat(response.warnings()).anyMatch(warning -> warning.contains("라벨이나 밑창 사진"));
        assertThat(response.defects()).hasSize(1);
    }

    @Test
    void Vision_실패_상태면_실패_단계와_메시지가_응답에_채워진다() {
        ProductAnalysisSession session = ProductAnalysisSession.create();
        session.markImageUploaded(List.of("https://bucket.s3.amazonaws.com/shoe.jpg"));
        session.markQueued();
        session.startVisionProcessing();
        session.failVision("OpenAI 호출 실패");
        when(sessionRepository.findById(1L)).thenReturn(Optional.of(session));

        AnalysisStatusResponse response = newService().getStatus(1L);

        assertThat(response.status()).isEqualTo("VISION_FAILED");
        assertThat(response.failureStage()).isEqualTo(AnalysisFailureStage.VISION.name());
        assertThat(response.failureMessage()).isEqualTo("OpenAI 호출 실패");
    }
}
