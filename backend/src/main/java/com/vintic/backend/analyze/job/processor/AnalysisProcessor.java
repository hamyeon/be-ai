package com.vintic.backend.analyze.job.processor;

// AI/가격 계산 로직을 격리하는 순수 계산 경계 (ADR-17).
//
// 구현체는 DB 저장, job 상태 전이, SQS 삭제(ack)를 하지 않는다 - 그 책임은 호출자(Worker
// 서비스)가 한 트랜잭션에서 진다. 일시적 오류는 AnalysisTransientFailureException,
// 영구 오류는 AnalysisPermanentFailureException으로 던진다.
public interface AnalysisProcessor {

    AnalysisPayload process(AnalysisInput input);
}
