package com.vintic.backend.common.exception;

public class AnalysisQueueException extends RuntimeException {
    public AnalysisQueueException(String message) {
        super(message);
    }

    public AnalysisQueueException(String message, Throwable cause) {
        super(message, cause);
    }
}
