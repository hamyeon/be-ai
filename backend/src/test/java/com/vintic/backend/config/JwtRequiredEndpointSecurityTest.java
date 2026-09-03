package com.vintic.backend.config;

import com.vintic.backend.auth.jwt.JwtProperties;
import com.vintic.backend.auth.jwt.JwtTokenProvider;
import com.vintic.backend.auth.security.JwtAuthenticationEntryPoint;
import com.vintic.backend.auth.security.JwtAuthenticationFilter;
import com.vintic.backend.config.ClockConfig;
import com.vintic.backend.penalty.PenaltyController;
import com.vintic.backend.penalty.dto.MyPenaltyResponse;
import com.vintic.backend.penalty.service.PenaltyQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// #75-4B: dev/prod SecurityFilterChain(JwtSecurityConfig) + JwtAuthenticationFilter +
// JwtAuthenticationEntryPoint를 required endpoint(GET /api/me/penalties,
// @RequestAttribute("currentUserId"))로 검증한다. 실제 Auction/Order/BackupOffer/Notification
// 컨트롤러 회귀는 각자의 기존 슬라이스 테스트를 그대로 재실행해 확인한다(새로 만들지 않는다).
@WebMvcTest(PenaltyController.class)
@ActiveProfiles("dev")
@Import({
        JwtSecurityConfig.class, JwtAuthenticationFilter.class, JwtAuthenticationEntryPoint.class,
        JwtTokenProvider.class, JwtProperties.class, ClockConfig.class
})
@TestPropertySource(properties = "jwt.secret=required-endpoint-test-secret-32-bytes-minimum!!")
class JwtRequiredEndpointSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private JwtProperties jwtProperties;

    @MockitoBean
    private PenaltyQueryService penaltyQueryService;

    @Test
    void 토큰없이_요청하면_401과_40101을_반환한다() throws Exception {
        mockMvc.perform(get("/api/me/penalties"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value(40101));
    }

    @Test
    void 유효한_Access_Token이면_currentUserId가_전달되어_정상_응답한다() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        when(penaltyQueryService.getMyPenalties(5L))
                .thenReturn(new MyPenaltyResponse(0, false, null, now, List.of()));
        String token = jwtTokenProvider.issueAccessToken(5L);

        mockMvc.perform(get("/api/me/penalties").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(penaltyQueryService).getMyPenalties(5L);
    }

    @Test
    void malformed_token이면_401과_40101을_반환한다() throws Exception {
        mockMvc.perform(get("/api/me/penalties").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value(40101));
    }

    @Test
    void 서명이_다른_토큰이면_401과_40101을_반환한다() throws Exception {
        JwtProperties otherProperties = new JwtProperties();
        otherProperties.setSecret("a-totally-different-secret-32-bytes-minimum!!");
        otherProperties.setAccessTtlSeconds(1800);
        otherProperties.setRefreshTtlSeconds(1_209_600);
        JwtTokenProvider otherProvider = new JwtTokenProvider(otherProperties, Clock.systemUTC());
        String token = otherProvider.issueAccessToken(5L);

        mockMvc.perform(get("/api/me/penalties").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value(40101));
    }

    @Test
    void 만료된_토큰이면_401과_40101을_반환한다() throws Exception {
        Clock pastClock = Clock.fixed(
                Instant.now().minusSeconds(jwtProperties.getAccessTtlSeconds() + 60), ZoneId.of("Asia/Seoul")
        );
        JwtTokenProvider expiredIssuer = new JwtTokenProvider(jwtProperties, pastClock);
        String token = expiredIssuer.issueAccessToken(5L);

        mockMvc.perform(get("/api/me/penalties").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value(40101));
    }

    @Test
    void Refresh_Token을_Access_용도로_사용하면_401과_40101을_반환한다() throws Exception {
        String refreshToken = jwtTokenProvider.issueRefreshToken(5L);

        mockMvc.perform(get("/api/me/penalties").header("Authorization", "Bearer " + refreshToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value(40101));
    }
}
