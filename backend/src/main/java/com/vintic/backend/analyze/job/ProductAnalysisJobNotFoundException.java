package com.vintic.backend.analyze.job;

// 존재하지 않는 ProductAnalysisJob 조회/재발행 (404 Not Found)
public class ProductAnalysisJobNotFoundException extends RuntimeException {
    public ProductAnalysisJobNotFoundException(String message) {
        super(message);
    }
}
