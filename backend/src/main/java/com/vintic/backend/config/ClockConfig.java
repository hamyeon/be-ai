package com.vintic.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

// 애플리케이션 기준 timezone은 Asia/Seoul로 고정한다(DB 컬럼 타입은 LocalDateTime 그대로,
// migration 없음). 시간이 필요한 서비스는 LocalDateTime.now()/Instant.now()를 직접 부르지 않고
// 이 Clock을 주입받는다 - 테스트에서 같은 Clock 타입을 Clock.fixed(...)로 교체해 결정적으로
// 검증할 수 있게 하기 위함이다. production에서는 이 빈만 사용하고 Clock.fixed는 테스트 전용이다.
@Configuration
public class ClockConfig {

    public static final ZoneId APP_ZONE = ZoneId.of("Asia/Seoul");

    @Bean
    public Clock clock() {
        return Clock.system(APP_ZONE);
    }
}
