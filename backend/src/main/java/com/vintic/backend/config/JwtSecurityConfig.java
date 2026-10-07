package com.vintic.backend.config;

import com.vintic.backend.auth.security.JwtAuthenticationFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

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
//   GET  /api/products
// 그 외 전부(POST /api/products 포함) authenticated() - 나머지 required endpoint를 개별
// 나열하지 않고 anyRequest()로 처리한다(§0-A 기준 이 시점에 새 endpoint가 추가되지 않았다는
// 전제, 새 endpoint가 생기면 이 목록을 다시 감사해야 한다).
//
// AWS 공개 시연 배포(2026-09): POST /api/products/analyze와 GET /api/products/analyze/{taskId}를
// anonymous 목록에서 제거했다 - analyze는 유료 OpenAI Vision 호출을 트리거하므로, 인증 없는 외부
// 시연 주소에서는 익명 남용을 막기 위해 로그인을 요구한다(ProductAnalysisSession.userId 소유자
// 검증과 짝을 이룬다 - ProductAnalyzeService.getStatus() 참고).
//
// 같은 이유로 POST /api/products/calculate-price도 anonymous에서 제거했다(2026-10) - analyze만
// 막고 이건 열어두면, 순차 증가하는 analysisId로 남의 세션을 COMPLETED로 만들어(세션당 1회 계산)
// 소유자의 가격 계산을 막을 수 있었다. ProductPricingService.calculatePrice()의 소유자 검증과 짝을 이룬다.
//
// 같은 배포 작업에서 발견: management.server.port(기본 8081)로 actuator를 분리해도 이 필터체인이
// 그 포트의 요청에도 적용된다(로컬에서 실측 확인 - management.server.port를 달리 줘도 별도
// SecurityFilterChain이 자동 생성되지 않는다). anyRequest().authenticated()에 그대로 걸리면
// scripts/aws/deploy.sh의 readiness 확인(GET /actuator/health)이 401만 돌려받아 배포마다 항상
// 실패한다.
//
// permitAll은 /actuator/health(+하위 컴포넌트 경로)로만 좁힌다 - 배포 자동화가 실제로 필요한
// 건 이것뿐이다. metrics/info는 여기서 열지 않는다 - docs/deployment-config.md가 이미
// "8081을 보안그룹에서 차단하고, 필요하면 EC2 안에서 curl로 접근"하도록 설계해 뒀다(AI 호출
// 지표 등 운영 정보가 담겨 있어 인증 없이 인터넷에 노출할 이유가 없다). health는 상태
// UP/DOWN과(show-details=never 배포 기본값이면) 컴포넌트 이름 정도만 담겨 있어 노출 위험이
// 낮다 - env/configprops/beans/heapdump는 애초에 노출 목록(MANAGEMENT_ENDPOINTS)에 없어
// 여기서 permitAll을 넓혀도 열리지 않는다.
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
            "/api/products"
    };

    // 공개 AWS 시연 배포에서 브라우저 프론트(다른 오리진)가 이 API를 호출하려면 필요하다 - 로컬
    // 개발(같은 오리진 또는 CORS를 신경 쓰지 않는 도구로 호출)에서는 비워두면 된다. 인증이 쿠키가
    // 아니라 Authorization 헤더(Bearer JWT) 기반이라 allowCredentials는 필요 없다.
    //
    // setAllowedOriginPatterns()를 쓴다(setAllowedOrigins()가 아니다) - LAN에서 접속하는 기기마다
    // IP가 달라(예: http://192.168.1.23:5173, http://192.168.0.7:5173) 정확히 일치하는 origin을
    // 전부 나열할 수 없다. 패턴 문자열의 "*"만 임의 길이 와일드카드로 동작하고("*" 없는 항목,
    // 예: http://localhost:5173/capacitor://localhost/https://localhost는 정확히 그 문자열만
    // 매칭한다) - 리터럴 "*" 하나로 전체 오리진을 여는 것과는 다르다(아래 검증으로 막는다).
    @Value("${cors.allowed-origins:}")
    private String allowedOrigins;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        List<String> originPatterns = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();
        // 전체 오리진 허용("*")은 금지한다 - 설정 실수로 들어오면 기동 시점에 바로 실패시켜
        // 조용히 전체 공개로 넘어가지 않게 한다(S3Config의 반쪽짜리 설정 거부와 동일 원칙).
        if (originPatterns.contains("*")) {
            throw new IllegalStateException(
                    "cors.allowed-origins(CORS_ALLOWED_ORIGINS)에 전체 허용(\"*\")은 사용할 수 없습니다 - "
                            + "허용할 origin을 명시적으로 나열하세요."
            );
        }

        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(originPatterns);
        configuration.setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key"));
        configuration.setAllowCredentials(false);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public SecurityFilterChain jwtFilterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            AuthenticationEntryPoint authenticationEntryPoint,
            CorsConfigurationSource corsConfigurationSource
    ) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(authenticationEntryPoint))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers(HttpMethod.GET, ANONYMOUS_GET_PATHS).permitAll()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
