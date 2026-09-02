package com.vintic.backend.common.exception;

// Kakao 5xx/timeout/network 등 예기치 않은 upstream 실패. 메시지에 원본 Kakao payload/예외
// 메시지를 담지 않는다 - GlobalExceptionHandler가 이 예외의 getMessage()를 그대로 클라이언트에
// 노출하지 않고 고정 문구만 반환한다(그래도 원인 파악을 위해 cause는 로그용으로 남긴다).
public class KakaoApiException extends RuntimeException {
    public KakaoApiException(String message) {
        super(message);
    }

    public KakaoApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
