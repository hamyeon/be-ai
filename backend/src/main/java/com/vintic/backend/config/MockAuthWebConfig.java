package com.vintic.backend.config;

import com.vintic.backend.common.auth.mock.MockAuthInterceptor;
import com.vintic.backend.common.auth.mock.MockUserRegistry;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

// local profile에서만 등록되는 mock 인증 설정. local이 아니면 이 클래스 자체가 빈으로 생성되지 않아
// MockAuthInterceptor도 절대 등록되지 않는다(허용 목록 방식이라 다른 profile이 추가돼도 안전).
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
