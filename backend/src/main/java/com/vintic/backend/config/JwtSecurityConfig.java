package com.vintic.backend.config;

import com.vintic.backend.auth.security.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

// #75-4B, dev/prod 전용. JWT 기반 stateless SecurityFilterChain - HTTP Basic/form login 비활성화,
// CSRF 비활성화(REST API, 세션 없음), 세션 STATELESS.
//
// endpoint 정책은 #75-0에서 확정한 anonymous/required matrix를 그대로 옮긴 것이다 - 임의로
// 넓히거나 좁히지 않는다. anonymous 허용:
//   GET  /api/auctions/{auctionId}
//   GET  /api/auctions/{auctionId}/bids
//   GET  /api/auctions/{auctionId}/similar
//   GET  /api/recommendations/auctions
//   GET  /api/curations
//   POST /api/products/analyze
//   GET  /api/products/analyze/{taskId}
//   POST /api/products/calculate-price
//   GET  /api/products
// 그 외 전부(POST /api/products 포함) authenticated() - 나머지 required endpoint를 개별
// 나열하지 않고 anyRequest()로 처리한다(§0-A 기준 이 시점에 새 endpoint가 추가되지 않았다는
// 전제, 새 endpoint가 생기면 이 목록을 다시 감사해야 한다).
//
// #75-4C: POST /api/auth/kakao(로그인 자체이므로 anonymous)도 permitAll에 추가한다.
@Configuration
@Profile({"dev", "prod"})
public class JwtSecurityConfig {

    private static final String[] ANONYMOUS_GET_PATHS = {
            "/api/auctions/{auctionId}",
            "/api/auctions/{auctionId}/bids",
            "/api/auctions/{auctionId}/similar",
            "/api/recommendations/auctions",
            "/api/curations",
            "/api/products/analyze/{taskId}",
            "/api/products"
    };

    private static final String[] ANONYMOUS_POST_PATHS = {
            "/api/products/analyze",
            "/api/products/calculate-price"
    };

    @Bean
    public SecurityFilterChain jwtFilterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            AuthenticationEntryPoint authenticationEntryPoint
    ) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(authenticationEntryPoint))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers(HttpMethod.GET, ANONYMOUS_GET_PATHS).permitAll()
                        .requestMatchers(HttpMethod.POST, ANONYMOUS_POST_PATHS).permitAll()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
