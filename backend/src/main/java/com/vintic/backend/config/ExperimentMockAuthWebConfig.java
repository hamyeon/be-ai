package com.vintic.backend.config;

import com.vintic.backend.common.auth.mock.MockAuthInterceptor;
import com.vintic.backend.common.auth.mock.MockUserRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

// Day7 AWS 수동 배포 준비(내부 인프라 실험) 전용. MockAuthWebConfig(@Profile("local"))를
// 그대로 복제하지 않고 같은 MockAuthInterceptor/MockUserRegistry를 재사용만 한다 - X-User-Id
// 헤더 검증 로직/오류 계약(40101)은 완전히 동일하다.
//
// experiment-api profile 하나만으로는 활성화되지 않는다 - experiment.auth.mock-enabled=true
// (EXPERIMENT_AUTH_MOCK_ENABLED 환경변수)까지 함께 있어야 한다(이중 게이트). Day7 API EC2
// env에만 이 값을 true로 넣고 Worker에는 넣지 않는다.
//
// 이 인증은 X-User-Id 헤더를 그대로 신뢰하는 mock 계약이다 - 내부 SSM 기반 smoke test 전용이며,
// Day7 시점에는 API SG에 inbound가 없어 외부에서 도달할 수 없다. ALB 등으로 인터넷에 노출되는
// 환경(Day8+)에서는 이 설정을 절대 켜면 안 된다.
@Configuration
@Profile("experiment-api")
@ConditionalOnProperty(prefix = "experiment.auth", name = "mock-enabled", havingValue = "true")
public class ExperimentMockAuthWebConfig implements WebMvcConfigurer {

    private final MockUserRegistry mockUserRegistry;

    public ExperimentMockAuthWebConfig(MockUserRegistry mockUserRegistry) {
        this.mockUserRegistry = mockUserRegistry;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new MockAuthInterceptor(mockUserRegistry))
                .addPathPatterns("/**")
                .excludePathPatterns("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**");
    }
}
