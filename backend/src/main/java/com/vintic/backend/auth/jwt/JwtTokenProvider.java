package com.vintic.backend.auth.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

// Access/Refresh JWT 발급·검증 전담 component(#75-4A). 이번 단계는 이 primitive를 독립적으로
// 완성하는 것이 목적이다 - Kakao 연동/SecurityFilterChain/Controller 연결은 다음 단계(#75-4B/4C).
//
// subject = 내부 User.id(문자열)뿐이다 - email/nickname/profileImage 등 프로필 정보는 담지
// 않는다(authorization source of truth는 내부 userId, #75 사용자 확정).
//
// signature/malformed/expired 오류는 JJWT가 던지는 예외 타입(SignatureException/
// MalformedJwtException/ExpiredJwtException, 모두 JwtException 하위)을 그대로 전파한다 - 이번
// 단계에서 별도로 감싸거나 GlobalExceptionHandler/AuthenticationEntryPoint에 연결하지 않는다
// (그 매핑은 Security 단계의 책임). token type(ACCESS/REFRESH) 불일치만 이 컴포넌트가 직접
// 검증해 InvalidTokenTypeException을 던진다 - JJWT가 알지 못하는 이 프로젝트만의 규칙이기
// 때문이다.
//
// production Clock 빈은 Clock.system(Asia/Seoul)(ClockConfig)이고, 테스트는 Clock.fixed(...)로
// 교체해 발급/만료 시각을 결정적으로 검증한다(TimePolicy 문서의 기존 Clock 주입 관례와 동일).
//
// #75-4B: dev/prod에서만 이 빈을 만든다(JwtProperties와 동일한 profile 제한 이유).
@Component
@Profile({"dev", "prod"})
public class JwtTokenProvider {

    private static final String CLAIM_TYPE = "type";

    private final JwtProperties jwtProperties;
    private final Clock clock;
    private final SecretKey key;

    public JwtTokenProvider(JwtProperties jwtProperties, Clock clock) {
        this.jwtProperties = jwtProperties;
        this.clock = clock;
        this.key = Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    public String issueAccessToken(Long userId) {
        return issue(userId, TokenType.ACCESS, jwtProperties.getAccessTtlSeconds());
    }

    public String issueRefreshToken(Long userId) {
        return issue(userId, TokenType.REFRESH, jwtProperties.getRefreshTtlSeconds());
    }

    public JwtClaims parseAccessToken(String token) {
        return toJwtClaims(parseAndValidateType(token, TokenType.ACCESS));
    }

    public JwtClaims parseRefreshToken(String token) {
        return toJwtClaims(parseAndValidateType(token, TokenType.REFRESH));
    }

    private String issue(Long userId, TokenType type, long ttlSeconds) {
        Instant issuedAt = Instant.now(clock);
        Instant expiresAt = issuedAt.plusSeconds(ttlSeconds);

        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(CLAIM_TYPE, type.name())
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();
    }

    // signature/expiration 검증은 parseSignedClaims() 호출 한 번에서 함께 일어난다(JJWT의 기본
    // 동작) - 실패하면 SignatureException/MalformedJwtException/ExpiredJwtException이 던져진다.
    // .clock(...)에 주입된 Clock을 그대로 넘겨 expiration 판정도 결정적으로 테스트할 수 있게 한다.
    private Claims parseAndValidateType(String token, TokenType expectedType) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .clock(() -> Date.from(Instant.now(clock)))
                .build()
                .parseSignedClaims(token)
                .getPayload();

        String actualType = claims.get(CLAIM_TYPE, String.class);
        if (!expectedType.name().equals(actualType)) {
            throw new InvalidTokenTypeException(
                    "기대한 토큰 타입이 아닙니다. expected: " + expectedType + ", actual: " + actualType
            );
        }
        return claims;
    }

    private JwtClaims toJwtClaims(Claims claims) {
        return new JwtClaims(
                Long.valueOf(claims.getSubject()),
                claims.getId(),
                TokenType.valueOf(claims.get(CLAIM_TYPE, String.class)),
                claims.getExpiration().toInstant()
        );
    }
}
