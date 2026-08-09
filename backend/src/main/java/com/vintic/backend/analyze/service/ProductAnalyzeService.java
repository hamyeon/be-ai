package com.vintic.backend.analyze.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.ai.vision.dto.VisionAnalysisResult;
import com.vintic.backend.analyze.domain.ProductAnalysisSession;
import com.vintic.backend.analyze.domain.ProductAnalysisSessionRepository;
import com.vintic.backend.analyze.dto.AnalysisStatusResponse;
import com.vintic.backend.analyze.dto.AnalyzeAcceptedResponse;
import com.vintic.backend.analyze.queue.AnalysisTaskMessage;
import com.vintic.backend.analyze.queue.AnalysisTaskProducer;
import com.vintic.backend.common.exception.AnalysisSessionNotFoundException;
import com.vintic.backend.common.exception.InvalidImageException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

// 이미지 검증 -> S3 업로드 -> Queue 적재까지만 담당하는 오케스트레이터(Producer 쪽).
// 실제 Vision 분석은 이 서비스가 아니라 AnalysisTaskConsumer가 큐 메시지를 받아 비동기로 처리한다.
@Service
@RequiredArgsConstructor
@Slf4j
public class ProductAnalyzeService {

    private static final int FAILURE_MESSAGE_MAX_LENGTH = 1000;

    // Vision 분석 전/실패 상태에서 응답을 채울 때 쓰는 빈 결과
    private static final VisionAnalysisResult EMPTY_VISION_RESULT = new VisionAnalysisResult(
            null, null, null, null, null, null, null, null, null,
            List.of(), List.of(), List.of(), List.of()
    );

    private final S3UploaderService s3Service;
    private final ProductAnalysisSessionRepository sessionRepository;
    private final AnalysisFailureRecorder failureRecorder;
    private final AnalysisTaskProducer analysisTaskProducer;
    private final ObjectMapper objectMapper;

    public AnalyzeAcceptedResponse submitForAnalysis(List<MultipartFile> imageFiles) {

        // 방어 로직: 리스트 자체가 null이거나 비어있는지, 첫 번째 파일이 비어있는지 확인
        if (imageFiles == null || imageFiles.isEmpty() || imageFiles.get(0).isEmpty()) {
            throw new InvalidImageException("이미지 파일이 존재하지 않습니다.");
        }

        ProductAnalysisSession session = ProductAnalysisSession.create();
        sessionRepository.save(session);

        List<String> imageUrls;
        try {
            imageUrls = s3Service.uploadImages(imageFiles);
        } catch (RuntimeException e) {
            recordFailureSafely(() -> failureRecorder.recordImageUploadFailure(session.getId(), truncate(e.getMessage())));
            throw e;
        }

        session.markImageUploaded(imageUrls);
        sessionRepository.save(session);

        try {
            analysisTaskProducer.enqueue(new AnalysisTaskMessage(session.getId(), imageUrls));
        } catch (RuntimeException e) {
            recordFailureSafely(() -> failureRecorder.recordQueueingFailure(session.getId(), truncate(e.getMessage())));
            throw e;
        }

        session.markQueued();
        sessionRepository.save(session);

        return new AnalyzeAcceptedResponse(session.getId(), session.getStatus().name());
    }

    public AnalysisStatusResponse getStatus(Long analysisId) {
        ProductAnalysisSession session = sessionRepository.findById(analysisId)
                .orElseThrow(() -> new AnalysisSessionNotFoundException(
                        "분석 세션을 찾을 수 없습니다. analysisId: " + analysisId
                ));

        // 아직 분석 전이거나 결과를 못 읽으면 빈 결과로 대체한다.
        // 필드마다 null 검사를 반복하는 것보다 읽기 쉽고, 리스트 필드가 null 대신 빈 배열로 나간다.
        VisionAnalysisResult vision = parseVisionResult(session.getVisionResultJson());
        if (vision == null) {
            vision = EMPTY_VISION_RESULT;
        }

        return new AnalysisStatusResponse(
                session.getId(),
                session.getStatus().name(),
                session.getImageUrls(),
                vision.brand(),
                vision.modelName(),
                vision.color(),
                vision.size(),
                vision.boxIncluded(),
                vision.conditionDescription(),
                vision.conditionGrade() != null ? vision.conditionGrade().name() : null,
                nullToEmpty(vision.defects()),
                nullToEmpty(vision.candidates()),
                vision.confidence(),
                vision.needsUserConfirmation(),
                nullToEmpty(vision.warnings()),
                session.getFailureStage() != null ? session.getFailureStage().name() : null,
                session.getFailureMessage()
        );
    }

    private <T> List<T> nullToEmpty(List<T> values) {
        return values == null ? List.of() : values;
    }

    private VisionAnalysisResult parseVisionResult(String visionResultJson) {
        if (visionResultJson == null) {
            return null;
        }
        try {
            return objectMapper.readValue(visionResultJson, VisionAnalysisResult.class);
        } catch (JsonProcessingException e) {
            log.error("저장된 Vision 분석 결과를 읽는 중 오류가 발생했습니다. analysisId 조회에는 영향 없음", e);
            return null;
        }
    }

    // 실패 상태 기록 중 추가 오류가 나도, 원래 발생한 S3/Queue 예외가 덮어써지면 안 되므로
    // 여기서 삼키고 로그만 남긴다. 호출부는 항상 원래 예외를 다시 던진다.
    private void recordFailureSafely(Runnable recordAction) {
        try {
            recordAction.run();
        } catch (RuntimeException recordingError) {
            log.error("분석 세션 실패 상태 기록 중 추가 오류가 발생했습니다.", recordingError);
        }
    }

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > FAILURE_MESSAGE_MAX_LENGTH
                ? message.substring(0, FAILURE_MESSAGE_MAX_LENGTH)
                : message;
    }
}
