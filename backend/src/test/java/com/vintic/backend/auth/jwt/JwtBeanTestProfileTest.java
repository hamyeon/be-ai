package com.vintic.backend.auth.jwt;

import com.vintic.backend.config.ClockConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

// #75-4B: test profile도 local과 동일하게 JWT_SECRET 없이 context가 정상 기동해야 한다
// (JwtBeanLocalProfileTest와 동일한 근거, profile만 다르다).
@SpringBootTest(classes = {JwtProperties.class, JwtTokenProvider.class, ClockConfig.class})
@ActiveProfiles("test")
class JwtBeanTestProfileTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void JWT_SECRET_없이도_context가_기동하고_JWT_빈은_생성되지_않는다() {
        assertThat(applicationContext.getBeanNamesForType(JwtTokenProvider.class)).isEmpty();
        assertThat(applicationContext.getBeanNamesForType(JwtProperties.class)).isEmpty();
    }
}
