package com.vintic.backend.analyze.job.queue;

// Worker에게 전달하는 최소 payload. objectKey/userId/idempotencyKey 등 작업 정보는 담지 않는다 -
// DB(ProductAnalysisJob)가 상태 원본이고, Worker는 analysisId로 조회해서 필요한 값을 가져온다.
public record AnalysisJobQueueMessage(int eventVersion, Long analysisId) {

    public static final int CURRENT_EVENT_VERSION = 1;

    public static AnalysisJobQueueMessage forJob(Long analysisId) {
        return new AnalysisJobQueueMessage(CURRENT_EVENT_VERSION, analysisId);
    }
}
