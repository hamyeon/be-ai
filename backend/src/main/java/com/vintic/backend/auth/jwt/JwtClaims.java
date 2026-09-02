package com.vintic.backend.auth.jwt;

import java.time.Instant;

// #75-4C(Redis에 refresh:{jti} -> userId 저장)가 필요로 하는 userId/jti/expiration을 이 record
// 하나로 안정적으로 노출한다 - 호출자는 io.jsonwebtoken.Claims(원시 JWT 라이브러리 타입)를
// 직접 다루지 않는다.
public record JwtClaims(Long userId, String jti, TokenType type, Instant expiresAt) {
}
