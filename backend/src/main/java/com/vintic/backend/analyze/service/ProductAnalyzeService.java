package com.vintic.backend.analyze.service;

import com.vintic.backend.ai.vision.dto.VisionAnalysisRequest;
import com.vintic.backend.ai.vision.dto.VisionAnalysisResult;
import com.vintic.backend.ai.vision.service.VisionAnalysisService;
import com.vintic.backend.analyze.dto.AnalyzeResponse;
import com.vintic.backend.common.exception.InvalidImageException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

// 이미지 검증 -> S3 업로드 -> Vision 분석 순서로 흐름만 제어하는 오케스트레이터.
// 실제 Vision 호출/응답 변환 책임은 VisionAnalysisService 쪽에 있다.
@Service
@RequiredArgsConstructor
public class ProductAnalyzeService {

    private final S3UploaderService s3Service;
    private final VisionAnalysisService visionAnalysisService;

    public AnalyzeResponse processImageAndAnalyze(List<MultipartFile> imageFiles) {

        // 방어 로직: 리스트 자체가 null이거나 비어있는지, 첫 번째 파일이 비어있는지 확인
        if (imageFiles == null || imageFiles.isEmpty() || imageFiles.get(0).isEmpty()) {
            throw new InvalidImageException("이미지 파일이 존재하지 않습니다.");
        }

        // S3에 여러 이미지 업로드 후 URL 리스트 반환
        List<String> imageUrls = s3Service.uploadImages(imageFiles);

        VisionAnalysisResult result = visionAnalysisService.analyze(new VisionAnalysisRequest(imageUrls));

        return new AnalyzeResponse(
                imageUrls,
                result.brand(),
                result.modelName(),
                result.color(),
                result.size(),
                result.conditionDescription(),
                result.conditionGrade() != null ? result.conditionGrade().name() : null
        );
    }
}