package com.vintic.backend.auth;

import com.vintic.backend.auth.dto.RefreshResponse;
import com.vintic.backend.auth.jwt.InvalidTokenTypeException;
import com.vintic.backend.auth.jwt.JwtClaims;
import com.vintic.backend.auth.jwt.JwtTokenProvider;
import com.vintic.backend.common.exception.RefreshTokenInvalidException;
import com.vintic.backend.config.ClockConfig;
import io.jsonwebtoken.JwtException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

// #75-4D: POST /api/auth/refresh, POST /api/auth/logout의 orchestrator. Refresh Token
// rotation은 하지 않는다 - refresh()는 새 Access JWT만 발급하고 기존 Refresh JWT/Redis entry는
// 그대로 둔다. User DB는 다시 조회하지 않는다 - JWT subject(userId)만으로 새 Access JWT를
// 발급한다.
@Service
@Profile({"dev", "prod"})
public class RefreshTokenService {

    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenStore refreshTokenStore;

    public RefreshTokenService(JwtTokenProvider jwtTokenProvider, RefreshTokenStore refreshTokenStore) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.refreshTokenStore = refreshTokenStore;
    }

    public RefreshResponse refresh(String refreshToken) {
        JwtClaims claims = parseOrThrow(refreshToken);

        Long storedUserId = refreshTokenStore.findUserId(claims.jti())
                .orElseThrow(() -> new RefreshTokenInvalidException(
                        "등록되지 않았거나 이미 무효화된 Refresh Token입니다."
                ));
        if (!storedUserId.equals(claims.userId())) {
            // 이론상 도달하지 않는다 - Redis key는 발급 시점의 subject로만 저장되므로, 이 값이
            // 어긋난다는 것은 조작/충돌 가능성을 뜻한다. 조용히 신뢰하지 않고 명시적으로 막는다.
            throw new RefreshTokenInvalidException("Refresh Token의 사용자 정보가 일치하지 않습니다.");
        }

        String accessToken = jwtTokenProvider.issueAccessToken(claims.userId());
        JwtClaims accessClaims = jwtTokenProvider.parseAccessToken(accessToken);

        return new RefreshResponse(accessToken, OffsetDateTime.ofInstant(accessClaims.expiresAt(), ClockConfig.APP_ZONE));
    }

    // logout은 idempotent해야 한다(§5) - Redis entry가 이미 없어도(먼저 로그아웃됐거나 만료) 실패로
    // 취급하지 않는다. RefreshTokenStore.delete()가 존재하지 않는 key에 대해 조용히 no-op이므로
    // 별도 존재 확인 없이 그대로 호출하는 것만으로 이 요구사항이 만족된다. 단 Refresh Token
    // 자체가 malformed/서명 불일치/만료/Access token 오사용이면 그건 여전히 실패(40103)다 -
    // "암호학적으로 유효한 토큰인데 Redis에만 없는 경우"만 성공으로 본다.
    public void logout(String refreshToken) {
        JwtClaims claims = parseOrThrow(refreshToken);
        refreshTokenStore.delete(claims.jti());
    }

    private JwtClaims parseOrThrow(String refreshToken) {
        try {
            return jwtTokenProvider.parseRefreshToken(refreshToken);
        } catch (JwtException | InvalidTokenTypeException | IllegalArgumentException e) {
            throw new RefreshTokenInvalidException("유효하지 않은 Refresh Token입니다.", e);
        }
    }
}
