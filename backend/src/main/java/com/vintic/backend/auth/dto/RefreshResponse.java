package com.vintic.backend.auth.dto;

import java.time.OffsetDateTime;

public record RefreshResponse(String accessToken, OffsetDateTime accessTokenExpiresAt) {
}
