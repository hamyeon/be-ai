package com.vintic.backend.auth.security;

import com.vintic.backend.auth.jwt.InvalidTokenTypeException;
import com.vintic.backend.auth.jwt.JwtClaims;
import com.vintic.backend.auth.jwt.JwtTokenProvider;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

// #75-4B, dev/prod 전용. Authorization: Bearer {accessToken} -> Access JWT 검증 -> SecurityContext
// 설정 -> 기존 Controller 호환용 bridge(request.setAttribute("currentUserId", ...))까지만 한다.
//
// - Authorization 헤더가 없으면 그대로 통과시킨다(anonymous) - required/optional 판단은
//   JwtSecurityConfig의 authorizeHttpRequests()가 담당한다.
// - 헤더는 있는데 유효하지 않으면(malformed/서명 불일치/만료/Refresh token 오사용) endpoint가
//   anonymous 허용이든 아니든 즉시 401로 끊는다 - "유효하지 않은 토큰이 조용히 무시되고
//   anonymous로 통과"하는 경로를 만들지 않는다(§4 anonymous+invalid token -> 40101 요구사항).
// - business service를 호출하지 않고 User를 DB에서 재조회하지도 않는다 - JWT의 subject(userId)만
//   신뢰한다. JWT/Kakao 타입을 Controller/Service/domain으로 전달하지 않는다.
// - X-User-Id 헤더는 절대 읽지도, 합성하지도 않는다 - dev/prod의 identity source는
//   SecurityContext/JWT뿐이어야 한다(#75-4B 보안 마무리 수정). 외부에서 임의로 보낸 X-User-Id는
//   완전히 무시된다 - anonymous 허용 GET 3개(경매 상세/입찰이력/비슷한 상품)도 이제
//   @RequestAttribute(value="currentUserId", required=false)만 받는다.
// - #75-4D: /api/auth/**(로그인/refresh/logout)는 이 필터의 Access Token 검증 대상이 아니다 -
//   그 endpoint들의 실제 인증은 요청 바디의 credential(Kakao access token/Refresh JWT) 자체가
//   source of truth다. 이 필터가 그 경로까지 Authorization 헤더를 검사하면, 클라이언트가
//   (예: 만료된) Access Token을 실수로 함께 보냈을 때 로그인/refresh/logout 요청 자체가
//   40101로 막혀버린다 - permitAll이어도 필터 단계에서 이미 거부되는 모순이 생긴다.
@Component
@Profile({"dev", "prod"})
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String CURRENT_USER_ID_ATTRIBUTE = "currentUserId";
    private static final String AUTH_PATH_PREFIX = "/api/auth/";

    private final JwtTokenProvider jwtTokenProvider;
    private final AuthenticationEntryPoint authenticationEntryPoint;

    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider, AuthenticationEntryPoint authenticationEntryPoint) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.authenticationEntryPoint = authenticationEntryPoint;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        // getServletPath()가 아니라 getRequestURI()를 쓴다 - 이 앱은 server.servlet.context-path를
        // 설정하지 않아(항상 빈 문자열) getRequestURI()가 그대로 "/api/..." 형태이고, MockMvc가
        // 만드는 테스트 요청은 getServletPath()를 빈 문자열로 두는 경우가 있어 그쪽은 신뢰할 수 없다.
        if (request.getRequestURI().startsWith(AUTH_PATH_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = header.substring(BEARER_PREFIX.length());
        JwtClaims claims;
        try {
            claims = jwtTokenProvider.parseAccessToken(token);
        } catch (JwtException | InvalidTokenTypeException | IllegalArgumentException e) {
            // signature 불일치/malformed/expired(JJWT 예외)와 Refresh token 오사용(자체 타입)
            // 전부 여기로 수렴한다 - 이 필터는 원인을 구분해 응답하지 않는다(AuthenticationEntryPoint가
            // 40101 하나로 통일).
            authenticationEntryPoint.commence(request, response,
                    new BadCredentialsException("유효하지 않은 Access Token입니다.", e));
            return;
        }

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(claims.userId(), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);
        request.setAttribute(CURRENT_USER_ID_ATTRIBUTE, claims.userId());

        filterChain.doFilter(request, response);
    }
}
