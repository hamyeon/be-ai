package com.vintic.backend.auth.dto;

import java.time.OffsetDateTime;

public record KakaoLoginResponse(
        String accessToken,
        String refreshToken,
        OffsetDateTime accessTokenExpiresAt,
        OffsetDateTime refreshTokenExpiresAt
) {
}
