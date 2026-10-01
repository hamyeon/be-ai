package com.vintic.backend.config;

import com.vintic.backend.common.auth.mock.MockAuthInterceptor;
import com.vintic.backend.common.auth.mock.MockUserRegistry;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

// local profile 전용으로 등록되는 mock 인증 설정. 그 외 profile에서는 이 클래스 자체가 빈으로
// 생성되지 않아 MockAuthInterceptor도 절대 등록되지 않는다(허용 목록 방식이라 다른 profile이
// 추가돼도 안전).
//
// #75-4B: dev는 더 이상 여기 포함하지 않는다 - 배포 서버(dev)는 이제 JwtSecurityConfig/
// JwtAuthenticationFilter가 currentUserId request attribute를 채운다(#75-4A/4B). dev를 여기
// 남겨두면 실제 JWT 인증과 MockAuth(X-User-Id 헤더 그대로 신뢰)가 같은 profile에서 동시에
// 동작해 인증이 무의미해진다.
@Configuration
@Profile("local")
public class MockAuthWebConfig implements WebMvcConfigurer {

    private final MockUserRegistry mockUserRegistry;

    public MockAuthWebConfig(MockUserRegistry mockUserRegistry) {
        this.mockUserRegistry = mockUserRegistry;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new MockAuthInterceptor(mockUserRegistry))
                .addPathPatterns("/**")
                .excludePathPatterns("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**");
    }
}
