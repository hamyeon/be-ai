package com.vintic.backend.analyze.job.processor;

// AnalysisProcessor.process()의 최소 임시 결과. analysis_result의 최종 컬럼/DTO는
// ADR-17 baseline SHA 기준으로 스프린트 중 별도 확정하며, 여기서는 확정하지 않는다.
public record AnalysisPayload(String rawResult) {
}
