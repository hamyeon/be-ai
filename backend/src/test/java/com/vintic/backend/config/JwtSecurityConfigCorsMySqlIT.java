package com.vintic.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

// Vite 프록시를 거치지 않는 "브라우저가 직접 보내는" CORS 요청을 기준으로
// JwtSecurityConfig.corsConfigurationSource()를 검증한다 - TestRestTemplate으로 실제
// Origin/Access-Control-Request-* 헤더가 붙은 HTTP 요청을 dev SecurityFilterChain에 그대로
// 통과시킨다(JwtAuthorizationWiringMySqlIT와 동일한 원칙: dev profile을 띄워야 JwtSecurityConfig/
// JwtAuthenticationFilter가 활성화된다).
//
// cors.allowed-origins(CORS_ALLOWED_ORIGINS)에 이번에 실제로 허용하기로 한 4개 origin을 모두
// 주입해, "허용된 origin은 통과" + "허용되지 않은 origin은 여전히 차단"을 함께 확인한다 - 허용
// 목록을 넓히는 것만 검증하고 전체 개방 여부를 놓치지 않기 위함이다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@Testcontainers
class JwtSecurityConfigCorsMySqlIT {

    private static final String LOCALHOST_VITE_ORIGIN = "http://localhost:5173";
    private static final String LAN_ORIGIN = "http://192.168.1.23:5173";
    private static final String CAPACITOR_ORIGIN = "capacitor://localhost";
    private static final String HTTPS_LOCALHOST_ORIGIN = "https://localhost";
    private static final String DISALLOWED_ORIGIN = "https://evil.example.com";

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("jwt.secret", () -> "jwt-cors-regression-it-secret-32-bytes-minimum!!");
        registry.add("cors.allowed-origins", () -> String.join(
                ",", LOCALHOST_VITE_ORIGIN, "http://192.168.*.*:5173", CAPACITOR_ORIGIN, HTTPS_LOCALHOST_ORIGIN
        ));
        // 이 테스트의 관심사와 무관한 백그라운드 스케줄러가 실제 요청/응답 흐름에 끼어들지
        // 않게 꺼둔다(JwtAuthorizationWiringMySqlIT와 동일한 이유).
        registry.add("auction.lifecycle.start.enabled", () -> "false");
        registry.add("auction.lifecycle.end.enabled", () -> "false");
        registry.add("payment.expiration.enabled", () -> "false");
        registry.add("backup-offer.expiration.enabled", () -> "false");
    }

    @Autowired
    private TestRestTemplate restTemplate;

    private ResponseEntity<String> preflight(String origin, String requestMethod, String requestHeaders) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Origin", origin);
        headers.set("Access-Control-Request-Method", requestMethod);
        headers.set("Access-Control-Request-Headers", requestHeaders);
        return restTemplate.exchange(
                "/api/products", HttpMethod.OPTIONS, new HttpEntity<>(headers), String.class
        );
    }

    @Test
    void localhost_5173_origin의_preflight는_허용_Origin_메서드_헤더를_모두_포함해_성공한다() {
        ResponseEntity<String> response = preflight(LOCALHOST_VITE_ORIGIN, "GET", "Authorization,Content-Type");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        HttpHeaders responseHeaders = response.getHeaders();
        assertThat(responseHeaders.getFirst("Access-Control-Allow-Origin")).isEqualTo(LOCALHOST_VITE_ORIGIN);
        assertThat(responseHeaders.getFirst("Access-Control-Allow-Methods"))
                .contains("GET", "POST", "PATCH", "DELETE", "OPTIONS");
        assertThat(responseHeaders.getFirst("Access-Control-Allow-Headers"))
                .containsIgnoringCase("Authorization")
                .containsIgnoringCase("Content-Type");
    }

    // Idempotency-Key는 PATCH/POST에서만 쓰이지만 preflight 허용 헤더 목록 자체는 경로와
    // 무관하게 고정이므로, 이 요청에서 요구하지 않아도 전체 허용 목록에 남아있는지 확인한다.
    @Test
    void Idempotency_Key_헤더도_허용_헤더_목록에_남아있다() {
        ResponseEntity<String> response = preflight(LOCALHOST_VITE_ORIGIN, "POST", "Idempotency-Key,Content-Type");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst("Access-Control-Allow-Headers"))
                .containsIgnoringCase("Idempotency-Key");
    }

    @Test
    void LAN_와일드카드_패턴에_매칭되는_origin도_preflight가_허용된다() {
        ResponseEntity<String> response = preflight(LAN_ORIGIN, "GET", "Authorization");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // 패턴 매칭이므로 응답은 패턴이 아니라 실제 요청 Origin을 그대로 echo해야 한다.
        assertThat(response.getHeaders().getFirst("Access-Control-Allow-Origin")).isEqualTo(LAN_ORIGIN);
    }

    @Test
    void capacitor_origin도_preflight가_허용된다() {
        ResponseEntity<String> response = preflight(CAPACITOR_ORIGIN, "GET", "Authorization");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst("Access-Control-Allow-Origin")).isEqualTo(CAPACITOR_ORIGIN);
    }

    @Test
    void https_localhost_origin도_preflight가_허용된다() {
        ResponseEntity<String> response = preflight(HTTPS_LOCALHOST_ORIGIN, "GET", "Authorization");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst("Access-Control-Allow-Origin")).isEqualTo(HTTPS_LOCALHOST_ORIGIN);
    }

    // 허용 목록을 넓히는 것만 검증하고 전체 개방 여부를 놓치면 안 된다 - 목록에 없는 origin은
    // 여전히 CORS로 차단되어야 한다(403, Access-Control-Allow-Origin 없음).
    @Test
    void 허용_목록에_없는_origin은_preflight가_여전히_차단된다() {
        ResponseEntity<String> response = preflight(DISALLOWED_ORIGIN, "GET", "Authorization");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getHeaders().getFirst("Access-Control-Allow-Origin")).isNull();
    }

    // 실제 요청(OPTIONS가 아닌)에서도 허용된 origin이면 CORS 필터를 통과해 컨트롤러/Security
    // 인증 단계까지 도달해야 한다 - 토큰이 없으면 CORS 403이 아니라 인증 401이어야 한다(§요구사항).
    @Test
    void 허용된_origin에서_토큰_없이_보호된_엔드포인트를_호출하면_CORS_헤더가_포함된_401을_반환한다() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Origin", LOCALHOST_VITE_ORIGIN);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/products/analyze/999999", HttpMethod.GET, new HttpEntity<>(headers), String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getFirst("Access-Control-Allow-Origin")).isEqualTo(LOCALHOST_VITE_ORIGIN);
        assertThat(response.getBody()).contains("40101");
    }

    // 대조군: 허용되지 않은 origin이 같은 보호된 엔드포인트를 토큰 없이 호출하면 CORS 단계에서
    // 먼저 막혀야 한다(401이 아니라 403, Access-Control-Allow-Origin 없음) - 인증 로직과 CORS
    // 로직이 서로 다른 계층임을 함께 확인한다.
    @Test
    void 허용되지_않은_origin에서_같은_요청을_보내면_인증과_무관하게_CORS_403이다() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Origin", DISALLOWED_ORIGIN);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/products/analyze/999999", HttpMethod.GET, new HttpEntity<>(headers), String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getHeaders().getFirst("Access-Control-Allow-Origin")).isNull();
    }
}
