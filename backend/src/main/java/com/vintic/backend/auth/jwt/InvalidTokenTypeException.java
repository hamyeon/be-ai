package com.vintic.backend.auth.jwt;

// Access token을 Refresh 검증에, 또는 그 반대로 사용하려 할 때 던진다. 외부 API 오류 코드
// 매핑(예: 40101 통일)은 이번 단계의 책임이 아니다 - 다음 Security 단계에서 연결한다.
public class InvalidTokenTypeException extends RuntimeException {
    public InvalidTokenTypeException(String message) {
        super(message);
    }
}
