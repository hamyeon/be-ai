package com.vintic.backend.analyze.dto;

import java.util.List;

// GET /api/products/analyze/{taskId} 응답. 상태에 따라 Vision 필드나 실패 필드가 비어있을 수 있다.
public record AnalysisStatusResponse(
        Long analysisId,
        String status,
        List<String> imageUrls,
        String brand,
        String modelName,
        String color,
        Integer size,
        String conditionDescription,
        String conditionGrade,
        String failureStage,
        String failureMessage
) {
}
