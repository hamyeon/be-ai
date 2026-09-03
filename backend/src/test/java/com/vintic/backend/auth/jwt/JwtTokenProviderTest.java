package com.vintic.backend.auth.jwt;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.SignatureException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// #75-4A: Access/Refresh JWT primitive 단위 테스트. 실제 sleep 없이 Clock.fixed(...)로
// 발급/검증 시각을 결정적으로 제어한다(만료 테스트는 발급 시각과 검증 시각을 각각 다른
// Clock으로 고정한 두 JwtTokenProvider 인스턴스를 사용).
class JwtTokenProviderTest {

    private static final String SECRET = "unit-test-jwt-secret-key-please-keep-at-least-32-bytes-long";
    private static final Instant FIXED_NOW = Instant.parse("2026-08-17T11:00:00Z");
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final long ACCESS_TTL = 1800;
    private static final long REFRESH_TTL = 1_209_600;

    private JwtProperties properties(String secret) {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(secret);
        properties.setAccessTtlSeconds(ACCESS_TTL);
        properties.setRefreshTtlSeconds(REFRESH_TTL);
        return properties;
    }

    private JwtTokenProvider providerAt(Instant now) {
        return new JwtTokenProvider(properties(SECRET), Clock.fixed(now, ZONE));
    }

    @Test
    void Access_토큰을_발급하면_userId_type_jti가_올바르다() {
        JwtTokenProvider provider = providerAt(FIXED_NOW);

        String token = provider.issueAccessToken(42L);
        JwtClaims claims = provider.parseAccessToken(token);

        assertThat(claims.userId()).isEqualTo(42L);
        assertThat(claims.type()).isEqualTo(TokenType.ACCESS);
        assertThat(claims.jti()).isNotBlank();
    }

    @Test
    void Refresh_토큰을_발급하면_userId_type_jti가_올바르다() {
        JwtTokenProvider provider = providerAt(FIXED_NOW);

        String token = provider.issueRefreshToken(42L);
        JwtClaims claims = provider.parseRefreshToken(token);

        assertThat(claims.userId()).isEqualTo(42L);
        assertThat(claims.type()).isEqualTo(TokenType.REFRESH);
        assertThat(claims.jti()).isNotBlank();
    }

    @Test
    void 설정한_TTL만큼_만료시간이_계산된다() {
        JwtTokenProvider provider = providerAt(FIXED_NOW);

        String accessToken = provider.issueAccessToken(1L);
        String refreshToken = provider.issueRefreshToken(1L);

        assertThat(provider.parseAccessToken(accessToken).expiresAt()).isEqualTo(FIXED_NOW.plusSeconds(ACCESS_TTL));
        assertThat(provider.parseRefreshToken(refreshToken).expiresAt()).isEqualTo(FIXED_NOW.plusSeconds(REFRESH_TTL));
    }

    @Test
    void 잘못된_signature는_거부된다() {
        JwtTokenProvider issuer = providerAt(FIXED_NOW);
        JwtTokenProvider otherSecretProvider = new JwtTokenProvider(
                properties("a-completely-different-unit-test-secret-key-32-bytes-min!!"),
                Clock.fixed(FIXED_NOW, ZONE)
        );

        String token = issuer.issueAccessToken(1L);

        assertThatThrownBy(() -> otherSecretProvider.parseAccessToken(token))
                .isInstanceOf(SignatureException.class);
    }

    @Test
    void malformed_token은_거부된다() {
        JwtTokenProvider provider = providerAt(FIXED_NOW);

        assertThatThrownBy(() -> provider.parseAccessToken("not-a-jwt"))
                .isInstanceOf(MalformedJwtException.class);
    }

    @Test
    void expired_token은_거부된다() {
        JwtTokenProvider issuer = providerAt(FIXED_NOW);
        String token = issuer.issueAccessToken(1L);

        JwtTokenProvider laterProvider = providerAt(FIXED_NOW.plusSeconds(ACCESS_TTL + 1));

        assertThatThrownBy(() -> laterProvider.parseAccessToken(token))
                .isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void Access_토큰을_Refresh로_검증하면_거부된다() {
        JwtTokenProvider provider = providerAt(FIXED_NOW);
        String accessToken = provider.issueAccessToken(1L);

        assertThatThrownBy(() -> provider.parseRefreshToken(accessToken))
                .isInstanceOf(InvalidTokenTypeException.class);
    }

    @Test
    void Refresh_토큰을_Access로_검증하면_거부된다() {
        JwtTokenProvider provider = providerAt(FIXED_NOW);
        String refreshToken = provider.issueRefreshToken(1L);

        assertThatThrownBy(() -> provider.parseAccessToken(refreshToken))
                .isInstanceOf(InvalidTokenTypeException.class);
    }

    @Test
    void 서로_다른_발급_토큰의_jti는_다르다() {
        JwtTokenProvider provider = providerAt(FIXED_NOW);

        String token1 = provider.issueAccessToken(1L);
        String token2 = provider.issueAccessToken(1L);

        String jti1 = provider.parseAccessToken(token1).jti();
        String jti2 = provider.parseAccessToken(token2).jti();

        assertThat(jti1).isNotEqualTo(jti2);
    }
}
