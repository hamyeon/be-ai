package com.vintic.backend.common.exception;

// AnalysisProcessor.process()에서 재시도해도 성공할 수 없는 오류(잘못된 objectKey, 미지원
// 이미지 등)를 나타낸다. 호출자(Worker)는 이 예외를 잡아 job을 FAILED로 전이한다.
public class AnalysisPermanentFailureException extends RuntimeException {
    public AnalysisPermanentFailureException(String message) {
        super(message);
    }

    public AnalysisPermanentFailureException(String message, Throwable cause) {
        super(message, cause);
    }
}
