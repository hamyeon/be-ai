package com.vintic.backend.analyze.job.processor;

// AnalysisProcessor에 넘기는 최소 입력. Day3 SQS Worker가 필요로 하는 값만 담는다 -
// 최종 계산 입력 스키마는 확정하지 않는다.
// objectKey가 아니라 S3 GetObject로 읽은 실제 객체 내용을 담는다 - Processor는 S3에
// 직접 접근하지 않는다(ADR-17).
public record AnalysisInput(Long analysisId, byte[] imageContent) {
}
