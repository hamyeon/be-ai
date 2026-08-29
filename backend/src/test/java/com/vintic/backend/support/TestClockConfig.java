package com.vintic.backend.support;

import com.vintic.backend.config.ClockConfig;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.time.Clock;
import java.time.Instant;

// @DataJpaTest 슬라이스는 ClockConfig(@Configuration)를 로드하지 않아 Clock 빈이 없다.
// production은 절대 Clock.fixed를 쓰지 않는다 - 이 클래스는 테스트에서만 결정적 시각을 제공한다.
@TestConfiguration
public class TestClockConfig {

    public static final Instant FIXED_INSTANT = Instant.parse("2026-08-17T11:00:00Z"); // KST 20:00:00

    @Bean
    public Clock clock() {
        return Clock.fixed(FIXED_INSTANT, ClockConfig.APP_ZONE);
    }
}
