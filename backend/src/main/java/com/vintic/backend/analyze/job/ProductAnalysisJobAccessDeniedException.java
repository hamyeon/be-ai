package com.vintic.backend.analyze.job;

// 소유자가 아닌 사용자의 ProductAnalysisJob 조회 시도 (403 Forbidden)
public class ProductAnalysisJobAccessDeniedException extends RuntimeException {
    public ProductAnalysisJobAccessDeniedException(String message) {
        super(message);
    }
}
