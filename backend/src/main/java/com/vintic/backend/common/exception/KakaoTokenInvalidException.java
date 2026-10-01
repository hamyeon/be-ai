package com.vintic.backend.common.exception;

public class KakaoTokenInvalidException extends RuntimeException {
    public KakaoTokenInvalidException(String message) {
        super(message);
    }

    public KakaoTokenInvalidException(String message, Throwable cause) {
        super(message, cause);
    }
}
