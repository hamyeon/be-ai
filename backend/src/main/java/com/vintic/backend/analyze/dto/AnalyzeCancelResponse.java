package com.vintic.backend.analyze.dto;

// POST /api/products/analyze/{taskId}/cancel 응답(#127). 반복 취소해도 같은 값(status="CANCELLED")을
// 그대로 돌려준다 - ProductAnalyzeService.cancel()이 멱등하게 처리한다.
public record AnalyzeCancelResponse(
        Long analysisId,
        String status
) {
}
