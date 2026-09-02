package com.vintic.backend.auth.jwt;

import com.vintic.backend.config.ClockConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

// #75-4B: local profile은 JWT_SECRET을 설정하지 않는다 - JwtProperties/JwtTokenProvider가
// @Profile({"dev","prod"})로 제한되어 있어야 context가 정상 기동한다. classes=...로 범위를
// 좁혀 실제 DB/Redis 연결 없이 순수 profile/bean 생성 여부만 검증한다.
@SpringBootTest(classes = {JwtProperties.class, JwtTokenProvider.class, ClockConfig.class})
@ActiveProfiles("local")
class JwtBeanLocalProfileTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void JWT_SECRET_없이도_context가_기동하고_JWT_빈은_생성되지_않는다() {
        assertThat(applicationContext.getBeanNamesForType(JwtTokenProvider.class)).isEmpty();
        assertThat(applicationContext.getBeanNamesForType(JwtProperties.class)).isEmpty();
    }
}
