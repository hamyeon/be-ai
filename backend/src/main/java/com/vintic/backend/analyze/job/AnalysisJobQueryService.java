package com.vintic.backend.analyze.job;

import org.springframework.stereotype.Service;

@Service
public class AnalysisJobQueryService {

    private final ProductAnalysisJobRepository jobRepository;

    public AnalysisJobQueryService(ProductAnalysisJobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    public ProductAnalysisJob getOwnedJob(Long analysisId, Long userId) {
        ProductAnalysisJob job = jobRepository.findById(analysisId)
                .orElseThrow(() -> new ProductAnalysisJobNotFoundException(
                        "존재하지 않는 분석 작업입니다. analysisId: " + analysisId));

        if (!job.getUserId().equals(userId)) {
            throw new ProductAnalysisJobAccessDeniedException(
                    "접근 권한이 없는 분석 작업입니다. analysisId: " + analysisId);
        }

        return job;
    }
}
