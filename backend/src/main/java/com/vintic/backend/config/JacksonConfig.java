package com.vintic.backend.config;

import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.TimeZone;

// Jackson(jackson-datatype-jsr310)은 OffsetDateTime을 역직렬화할 때 기본적으로 ObjectMapper의
// timezone에 맞춰 오프셋을 재조정한다(ADJUST_DATES_TO_CONTEXT_TIME_ZONE). 이 timezone을 앱의
// 시간 정책(ClockConfig, Asia/Seoul)과 맞추지 않으면 Idempotency response_snapshot을 JSON으로
// 왕복시킨 replay 응답의 OffsetDateTime 필드가 +09:00 대신 UTC(Z)로 조용히 바뀐다 - FINAL contract가
// 요구하는 절대시각 형식과 달라지고, 최초 응답과도 값이 달라 보이게 된다(같은 instant인데 표기가 다름).
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer timeZoneCustomizer() {
        return builder -> builder.timeZone(TimeZone.getTimeZone(ClockConfig.APP_ZONE));
    }
}
