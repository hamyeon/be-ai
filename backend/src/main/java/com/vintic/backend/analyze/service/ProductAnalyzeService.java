package com.vintic.backend.analyze.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.ai.vision.dto.VisionAnalysisRequest;
import com.vintic.backend.ai.vision.dto.VisionAnalysisResult;
import com.vintic.backend.ai.vision.service.VisionAnalysisService;
import com.vintic.backend.analyze.domain.ProductAnalysisSession;
import com.vintic.backend.analyze.domain.ProductAnalysisSessionRepository;
import com.vintic.backend.analyze.dto.AnalyzeResponse;
import com.vintic.backend.common.exception.AiApiException;
import com.vintic.backend.common.exception.InvalidImageException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

// 이미지 검증 -> S3 업로드 -> Vision 분석 순서로 흐름을 제어하고, 단계마다 ProductAnalysisSession에 진행 상태를 기록하는 오케스트레이터.
// 실제 Vision 호출/응답 변환 책임은 VisionAnalysisService 쪽에 있다.
@Service
@RequiredArgsConstructor
@Slf4j
public class ProductAnalyzeService {

    private static final int FAILURE_MESSAGE_MAX_LENGTH = 1000;

    private final S3UploaderService s3Service;
    private final VisionAnalysisService visionAnalysisService;
    private final ProductAnalysisSessionRepository sessionRepository;
    private final AnalysisFailureRecorder failureRecorder;
    private final ObjectMapper objectMapper;

    public AnalyzeResponse processImageAndAnalyze(List<MultipartFile> imageFiles) {

        // 방어 로직: 리스트 자체가 null이거나 비어있는지, 첫 번째 파일이 비어있는지 확인
        if (imageFiles == null || imageFiles.isEmpty() || imageFiles.get(0).isEmpty()) {
            throw new InvalidImageException("이미지 파일이 존재하지 않습니다.");
        }

        ProductAnalysisSession session = ProductAnalysisSession.create();
        sessionRepository.save(session);

        // S3에 여러 이미지 업로드 후 URL 리스트 반환
        List<String> imageUrls;
        try {
            imageUrls = s3Service.uploadImages(imageFiles);
        } catch (RuntimeException e) {
            recordFailureSafely(() -> failureRecorder.recordImageUploadFailure(session.getId(), truncate(e.getMessage())));
            throw e;
        }

        session.markImageUploaded(imageUrls);
        sessionRepository.save(session);

        session.startVisionProcessing();
        sessionRepository.save(session);

        VisionAnalysisResult result;
        try {
            result = visionAnalysisService.analyze(new VisionAnalysisRequest(imageUrls));
        } catch (RuntimeException e) {
            recordFailureSafely(() -> failureRecorder.recordVisionFailure(session.getId(), truncate(e.getMessage())));
            throw e;
        }

        session.completeVision(toJson(result));
        sessionRepository.save(session);

        return new AnalyzeResponse(
                session.getId(),
                imageUrls,
                result.brand(),
                result.modelName(),
                result.color(),
                result.size(),
                result.conditionDescription(),
                result.conditionGrade() != null ? result.conditionGrade().name() : null
        );
    }

    // 실패 상태 기록 중 추가 오류가 나도, 원래 발생한 S3/Vision 예외가 덮어써지면 안 되므로
    // 여기서 삼키고 로그만 남긴다. 호출부는 항상 원래 예외를 다시 던진다.
    private void recordFailureSafely(Runnable recordAction) {
        try {
            recordAction.run();
        } catch (RuntimeException recordingError) {
            log.error("분석 세션 실패 상태 기록 중 추가 오류가 발생했습니다.", recordingError);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new AiApiException("분석 결과를 저장하는 중 오류가 발생했습니다.");
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
