package com.vintic.backend.common.exception;

// AnalysisProcessor.process()에서 재시도 대상 오류(AI timeout, 429, 5xx 등)를 나타낸다.
// 호출자(Worker)는 이 예외를 잡아 작업을 재시도 대상으로 남긴다 - job을 FAILED로 전이하지 않는다.
public class AnalysisTransientFailureException extends RuntimeException {
    public AnalysisTransientFailureException(String message) {
        super(message);
    }

    public AnalysisTransientFailureException(String message, Throwable cause) {
        super(message, cause);
    }
}
