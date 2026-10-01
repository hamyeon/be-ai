package com.vintic.backend.analyze.dto;

// POST /api/products/analyze의 즉시 응답(202 Accepted). Vision 분석 결과는 아직 없고,
// analysisId(taskId)로 GET /api/products/analyze/{taskId}를 폴링해서 확인해야 한다.
public record AnalyzeAcceptedResponse(
        Long analysisId,
        String status
) {
}
