package com.vintic.backend.config;

import com.vintic.backend.common.auth.mock.MockAuthInterceptor;
import com.vintic.backend.common.auth.mock.MockUserRegistry;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

// local/dev profile에서 등록되는 mock 인증 설정. 그 외 profile에서는 이 클래스 자체가 빈으로 생성되지
// 않아 MockAuthInterceptor도 절대 등록되지 않는다(허용 목록 방식이라 다른 profile이 추가돼도 안전).
//
// dev를 포함하는 이유: 배포 서버가 dev로 뜨는데 여기서 인터셉터가 빠지면 currentUserId를 받는 컨트롤러가
// 값을 채울 방법이 없어 상품 등록/입찰 API가 500으로 죽는다.
// 인터셉터를 /**에 걸어도 실제 헤더 검증은 currentUserId를 받는 핸들러에만 적용된다(MockAuthInterceptor).
@Configuration
@Profile({"local", "dev"})
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
