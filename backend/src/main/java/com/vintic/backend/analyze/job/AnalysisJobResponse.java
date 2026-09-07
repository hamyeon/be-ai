package com.vintic.backend.analyze.job;

public record AnalysisJobResponse(Long analysisId, String status) {

    public static AnalysisJobResponse from(ProductAnalysisJob job) {
        return new AnalysisJobResponse(job.getId(), job.getStatus().name());
    }
}
