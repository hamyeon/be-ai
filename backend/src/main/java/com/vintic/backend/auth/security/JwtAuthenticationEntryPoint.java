package com.vintic.backend.auth.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.common.dto.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

// #75-4B, dev/prod 전용. required endpoint에 토큰 없이 접근 / malformed / bad signature /
// expired / Refresh token을 Access 용도로 사용 - 원인과 무관하게 전부 기존 API error
// envelope(401 / code 40101)로 통일해 응답한다. JJWT 내부 예외 타입/메시지는 그대로 노출하지
// 않는다(JwtAuthenticationFilter가 원인을 구분해 로그를 남기더라도, 이 응답 자체는 항상 동일).
@Component
@Profile({"dev", "prod"})
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public JwtAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), ApiResponse.fail(40101, "인증이 필요합니다."));
    }
}
