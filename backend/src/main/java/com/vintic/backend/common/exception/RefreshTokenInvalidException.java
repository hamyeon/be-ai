package com.vintic.backend.common.exception;

// #75-4D: malformed/서명 불일치/만료/Access token 오사용/Redis entry 없음(revoked)/Redis
// userId와 JWT subject 불일치 - 원인과 무관하게 전부 이 예외 하나로 수렴한다(POST /api/auth/kakao
// 전용 40102와 혼동하지 않는다).
public class RefreshTokenInvalidException extends RuntimeException {
    public RefreshTokenInvalidException(String message) {
        super(message);
    }

    public RefreshTokenInvalidException(String message, Throwable cause) {
        super(message, cause);
    }
}
