package com.vintic.backend.ai.observability.repository;

import com.vintic.backend.ai.observability.domain.AiCallType;

// 호출 종류·프롬프트 버전·모델별 집계 한 줄.
//
// 프롬프트 버전을 묶음 키에 넣은 이유는 "v2로 바꾸고 나서 실패율이 늘었나"를
// 한 쿼리로 보기 위해서다.
public record AiCallStat(
        AiCallType callType,
        String promptVersion,
        String modelName,
        long totalCalls,
        long failedCalls,
        long promptTokens,
        long completionTokens,
        double averageLatencyMs
) {

    public long totalTokens() {
        return promptTokens + completionTokens;
    }

    public double failureRate() {
        return totalCalls == 0 ? 0.0 : (double) failedCalls / totalCalls;
    }
}
