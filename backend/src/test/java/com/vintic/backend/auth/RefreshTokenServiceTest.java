package com.vintic.backend.auth;

import com.vintic.backend.auth.dto.RefreshResponse;
import com.vintic.backend.auth.jwt.JwtProperties;
import com.vintic.backend.auth.jwt.JwtTokenProvider;
import com.vintic.backend.common.exception.RefreshTokenInvalidException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// #75-4D: RefreshTokenStore는 mock, JwtTokenProvider는 실제 JJWT primitive를 그대로 사용해
// 진짜 유효한 토큰으로 refresh/logout 흐름을 검증한다.
@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-09-02T11:00:00Z");
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    @Mock
    private RefreshTokenStore refreshTokenStore;

    private JwtTokenProvider jwtTokenProvider;
    private RefreshTokenService refreshTokenService;

    @BeforeEach
    void setUp() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret("refresh-token-service-test-secret-32-bytes-min!!");
        properties.setAccessTtlSeconds(1800);
        properties.setRefreshTtlSeconds(1_209_600);
        jwtTokenProvider = new JwtTokenProvider(properties, Clock.fixed(FIXED_NOW, ZONE));
        refreshTokenService = new RefreshTokenService(jwtTokenProvider, refreshTokenStore);
    }

    @Test
    void valid_refresh_토큰과_Redis_존재시_새_Access_Token을_발급한다() {
        String refreshToken = jwtTokenProvider.issueRefreshToken(7L);
        String jti = jwtTokenProvider.parseRefreshToken(refreshToken).jti();
        when(refreshTokenStore.findUserId(jti)).thenReturn(Optional.of(7L));

        RefreshResponse response = refreshTokenService.refresh(refreshToken);

        assertThat(jwtTokenProvider.parseAccessToken(response.accessToken()).userId()).isEqualTo(7L);
        assertThat(response.accessTokenExpiresAt()).isNotNull();
    }

    @Test
    void 기존_Refresh_Token은_재발급하지_않고_Redis도_다시_쓰지_않는다() {
        String refreshToken = jwtTokenProvider.issueRefreshToken(7L);
        String jti = jwtTokenProvider.parseRefreshToken(refreshToken).jti();
        when(refreshTokenStore.findUserId(jti)).thenReturn(Optional.of(7L));

        refreshTokenService.refresh(refreshToken);

        verify(refreshTokenStore, never()).save(anyString(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
        verify(refreshTokenStore, never()).delete(anyString());
    }

    @Test
    void malformed_토큰이면_40103에_해당하는_예외가_발생한다() {
        assertThatThrownBy(() -> refreshTokenService.refresh("not-a-jwt"))
                .isInstanceOf(RefreshTokenInvalidException.class);
    }

    @Test
    void 서명이_다른_토큰이면_예외가_발생한다() {
        JwtProperties otherProperties = new JwtProperties();
        otherProperties.setSecret("a-totally-different-secret-32-bytes-minimum!!");
        otherProperties.setAccessTtlSeconds(1800);
        otherProperties.setRefreshTtlSeconds(1_209_600);
        JwtTokenProvider otherProvider = new JwtTokenProvider(otherProperties, Clock.fixed(FIXED_NOW, ZONE));
        String token = otherProvider.issueRefreshToken(7L);

        assertThatThrownBy(() -> refreshTokenService.refresh(token))
                .isInstanceOf(RefreshTokenInvalidException.class);
    }

    @Test
    void 만료된_토큰이면_예외가_발생한다() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret("refresh-token-service-test-secret-32-bytes-min!!");
        properties.setAccessTtlSeconds(1800);
        properties.setRefreshTtlSeconds(1_209_600);
        JwtTokenProvider issuer = new JwtTokenProvider(properties, Clock.fixed(FIXED_NOW, ZONE));
        String token = issuer.issueRefreshToken(7L);
        JwtTokenProvider laterProvider = new JwtTokenProvider(
                properties, Clock.fixed(FIXED_NOW.plusSeconds(1_209_601), ZONE)
        );
        RefreshTokenService laterService = new RefreshTokenService(laterProvider, refreshTokenStore);

        assertThatThrownBy(() -> laterService.refresh(token))
                .isInstanceOf(RefreshTokenInvalidException.class);
    }

    @Test
    void Access_Token을_refresh에_제출하면_예외가_발생한다() {
        String accessToken = jwtTokenProvider.issueAccessToken(7L);

        assertThatThrownBy(() -> refreshTokenService.refresh(accessToken))
                .isInstanceOf(RefreshTokenInvalidException.class);
    }

    @Test
    void Redis에_entry가_없으면_예외가_발생한다() {
        String refreshToken = jwtTokenProvider.issueRefreshToken(7L);
        String jti = jwtTokenProvider.parseRefreshToken(refreshToken).jti();
        when(refreshTokenStore.findUserId(jti)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> refreshTokenService.refresh(refreshToken))
                .isInstanceOf(RefreshTokenInvalidException.class);
    }

    @Test
    void Redis에_저장된_userId가_JWT_subject와_다르면_예외가_발생한다() {
        String refreshToken = jwtTokenProvider.issueRefreshToken(7L);
        String jti = jwtTokenProvider.parseRefreshToken(refreshToken).jti();
        when(refreshTokenStore.findUserId(jti)).thenReturn(Optional.of(999L));

        assertThatThrownBy(() -> refreshTokenService.refresh(refreshToken))
                .isInstanceOf(RefreshTokenInvalidException.class);
    }

    @Test
    void valid_토큰으로_로그아웃하면_Redis_entry를_삭제한다() {
        String refreshToken = jwtTokenProvider.issueRefreshToken(7L);
        String jti = jwtTokenProvider.parseRefreshToken(refreshToken).jti();

        refreshTokenService.logout(refreshToken);

        verify(refreshTokenStore).delete(jti);
    }

    // idempotent: Redis에 이미 없어도(delete()가 mock이라 예외를 던지지 않음) 그대로 성공한다 -
    // 별도 존재 확인 없이 delete()만 호출하는 설계 자체가 이 요구사항을 만족시킨다.
    @Test
    void 동일_토큰으로_로그아웃을_재호출해도_예외없이_성공한다() {
        String refreshToken = jwtTokenProvider.issueRefreshToken(7L);

        refreshTokenService.logout(refreshToken);
        refreshTokenService.logout(refreshToken);

        String jti = jwtTokenProvider.parseRefreshToken(refreshToken).jti();
        verify(refreshTokenStore, org.mockito.Mockito.times(2)).delete(jti);
    }

    @Test
    void 로그아웃시_malformed_토큰은_예외가_발생한다() {
        assertThatThrownBy(() -> refreshTokenService.logout("not-a-jwt"))
                .isInstanceOf(RefreshTokenInvalidException.class);
    }

    @Test
    void 로그아웃시_Access_Token을_제출하면_예외가_발생한다() {
        String accessToken = jwtTokenProvider.issueAccessToken(7L);

        assertThatThrownBy(() -> refreshTokenService.logout(accessToken))
                .isInstanceOf(RefreshTokenInvalidException.class);
    }
}
