package com.vintic.backend.auth;

import com.vintic.backend.auth.dto.KakaoLoginResponse;
import com.vintic.backend.auth.dto.RefreshResponse;
import com.vintic.backend.auth.jwt.JwtProperties;
import com.vintic.backend.auth.jwt.JwtTokenProvider;
import com.vintic.backend.auth.security.JwtAuthenticationEntryPoint;
import com.vintic.backend.auth.security.JwtAuthenticationFilter;
import com.vintic.backend.common.exception.KakaoApiException;
import com.vintic.backend.common.exception.KakaoTokenInvalidException;
import com.vintic.backend.common.exception.RefreshTokenInvalidException;
import com.vintic.backend.config.ClockConfig;
import com.vintic.backend.config.JwtSecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// #75-4C/4D: dev SecurityFilterChain(JwtSecurityConfig)을 실제로 적용한 상태에서
// /api/auth/kakao, /api/auth/refresh, /api/auth/logout이 permitAll(anonymous 접근 가능)인지,
// 응답/에러 매핑이 맞는지 함께 검증한다. Authorization 헤더를 전혀 보내지 않는 모든 테스트가
// 401이 아니라 각자 기대한 상태코드를 받는다는 것 자체가 permitAll이 실제로 적용됐다는 증거다.
@WebMvcTest(AuthController.class)
@ActiveProfiles("dev")
@Import({
        JwtSecurityConfig.class, JwtAuthenticationFilter.class, JwtAuthenticationEntryPoint.class,
        JwtTokenProvider.class, JwtProperties.class, ClockConfig.class
})
@TestPropertySource(properties = "jwt.secret=auth-controller-test-secret-32-bytes-minimum!!")
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private KakaoLoginService kakaoLoginService;

    @MockitoBean
    private RefreshTokenService refreshTokenService;

    @Test
    void 유효한_Kakao_토큰이면_200과_토큰_쌍을_반환한다() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        when(kakaoLoginService.login("valid-kakao-token")).thenReturn(new KakaoLoginResponse(
                "access-jwt", "refresh-jwt", now.plusMinutes(30), now.plusDays(14)
        ));

        mockMvc.perform(post("/api/auth/kakao")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accessToken\":\"valid-kakao-token\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.accessToken").value("access-jwt"))
                .andExpect(jsonPath("$.data.refreshToken").value("refresh-jwt"))
                .andExpect(jsonPath("$.data.accessTokenExpiresAt").exists())
                .andExpect(jsonPath("$.data.refreshTokenExpiresAt").exists());
    }

    @Test
    void accessToken이_없으면_400과_40001을_반환한다() throws Exception {
        mockMvc.perform(post("/api/auth/kakao")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(40001));
    }

    @Test
    void Kakao_토큰이_invalid하면_401과_40102를_반환한다() throws Exception {
        when(kakaoLoginService.login("invalid-token"))
                .thenThrow(new KakaoTokenInvalidException("Kakao access token이 유효하지 않습니다."));

        mockMvc.perform(post("/api/auth/kakao")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accessToken\":\"invalid-token\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value(40102));
    }

    @Test
    void Kakao_API가_실패하면_502와_50201을_반환하고_내부_원인을_노출하지_않는다() throws Exception {
        when(kakaoLoginService.login("token"))
                .thenThrow(new KakaoApiException("Kakao 서버 내부 원인 상세: connection reset by peer"));

        mockMvc.perform(post("/api/auth/kakao")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accessToken\":\"token\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value(50201))
                .andExpect(jsonPath("$.error.message").value("Kakao 사용자 정보 조회에 실패했습니다."));
    }

    // #75-4D: /api/auth/kakao에 (만료됐거나 무효한) Authorization Bearer 헤더를 함께 보내도
    // JwtAuthenticationFilter가 이 경로를 건드리지 않으므로 정상 처리돼야 한다(§8).
    @Test
    void 유효하지_않은_Authorization_헤더를_함께_보내도_Kakao_로그인은_정상_처리된다() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        when(kakaoLoginService.login("valid-kakao-token")).thenReturn(new KakaoLoginResponse(
                "access-jwt", "refresh-jwt", now.plusMinutes(30), now.plusDays(14)
        ));

        mockMvc.perform(post("/api/auth/kakao")
                        .header("Authorization", "Bearer this-is-not-a-valid-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accessToken\":\"valid-kakao-token\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void valid_refresh_토큰이면_200과_새_Access_Token을_반환한다() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        when(refreshTokenService.refresh("valid-refresh-token"))
                .thenReturn(new RefreshResponse("new-access-jwt", now.plusMinutes(30)));

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"valid-refresh-token\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.accessToken").value("new-access-jwt"))
                .andExpect(jsonPath("$.data.accessTokenExpiresAt").exists());
    }

    @Test
    void refreshToken이_없으면_400과_40001을_반환한다() throws Exception {
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(40001));
    }

    @Test
    void 유효하지_않은_refresh_토큰이면_401과_40103을_반환한다() throws Exception {
        when(refreshTokenService.refresh("invalid"))
                .thenThrow(new RefreshTokenInvalidException("유효하지 않은 Refresh Token입니다."));

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"invalid\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value(40103));
    }

    // refresh와 동일하게 (만료됐거나 무효한) Authorization Bearer 헤더가 있어도 막히지 않는다.
    @Test
    void 유효하지_않은_Authorization_헤더를_함께_보내도_refresh는_정상_처리된다() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        when(refreshTokenService.refresh("valid-refresh-token"))
                .thenReturn(new RefreshResponse("new-access-jwt", now.plusMinutes(30)));

        mockMvc.perform(post("/api/auth/refresh")
                        .header("Authorization", "Bearer this-is-not-a-valid-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"valid-refresh-token\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void valid_토큰으로_로그아웃하면_200을_반환한다() throws Exception {
        mockMvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"valid-refresh-token\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(refreshTokenService).logout("valid-refresh-token");
    }

    @Test
    void refreshToken이_없으면_로그아웃도_400과_40001을_반환한다() throws Exception {
        mockMvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(40001));
    }

    @Test
    void malformed_토큰으로_로그아웃하면_401과_40103을_반환한다() throws Exception {
        org.mockito.Mockito.doThrow(new RefreshTokenInvalidException("유효하지 않은 Refresh Token입니다."))
                .when(refreshTokenService).logout("not-a-jwt");

        mockMvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"not-a-jwt\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value(40103));
    }

    // logout endpoint도 anonymous - Authorization 헤더 없이 접근 가능해야 한다(§8).
    @Test
    void Authorization_헤더없이_로그아웃해도_정상_처리된다() throws Exception {
        mockMvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"valid-refresh-token\"}"))
                .andExpect(status().isOk());
    }
}
