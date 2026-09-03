package com.vintic.backend.auth.jwt;

import com.vintic.backend.config.ClockConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

// #75-4B: dev profile에서 JWT_SECRET(등 필요한 property)이 있으면 JwtProperties/JwtTokenProvider
// 빈이 정상적으로 생성되는지 확인한다. classes=...로 범위를 좁혀 dev profile의 실제 DB(RDS)
// 연결 없이 순수 profile/bean 생성 여부만 검증한다.
//
// classes=...에 @SpringBootConfiguration/@EnableAutoConfiguration이 없으면 Boot의 auto-configuration
// import 자체가 전혀 일어나지 않는다 - ConfigurationPropertiesAutoConfiguration(=
// @ConfigurationProperties 실제 바인딩을 수행하는 ConfigurationPropertiesBindingPostProcessor를
// 등록하는 곳)도 빠지므로, @ConfigurationProperties 애노테이션이 있어도 값이 전혀 바인딩되지
// 않는다(JwtProperties 빈은 생성되지만 필드가 전부 기본값으로 남는다). 그래서 이 auto-configuration만
// 명시적으로 classes에 추가한다.
@SpringBootTest(classes = {
        JwtProperties.class, JwtTokenProvider.class, ClockConfig.class, ConfigurationPropertiesAutoConfiguration.class
})
@ActiveProfiles("dev")
@TestPropertySource(properties = "jwt.secret=dev-profile-test-secret-32-bytes-minimum-please!!")
class JwtBeanDevProfileTest {

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Test
    void JWT_SECRET이_있으면_JWT_빈이_생성되고_정상_동작한다() {
        String token = jwtTokenProvider.issueAccessToken(1L);

        assertThat(jwtTokenProvider.parseAccessToken(token).userId()).isEqualTo(1L);
    }
}
