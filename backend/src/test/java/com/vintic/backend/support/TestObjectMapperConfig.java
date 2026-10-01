package com.vintic.backend.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.vintic.backend.config.ClockConfig;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.TimeZone;

// @DataJpaTest 슬라이스는 Spring Boot의 JacksonAutoConfiguration(+ JacksonConfig)을 로드하지
// 않아 순수 `new ObjectMapper()`엔 JavaTimeModule도, 앱의 timezone 설정도 없다 - LocalDateTime/
// OffsetDateTime 필드가 있는 DTO 직렬화가 실패하거나(모듈 없음) OffsetDateTime replay가 UTC로
// 조용히 바뀐다(timezone 기본값 불일치, JacksonConfig 참고). 이 클래스는 프로덕션 설정을 흉내낸다.
@TestConfiguration
public class TestObjectMapperConfig {

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        objectMapper.setTimeZone(TimeZone.getTimeZone(ClockConfig.APP_ZONE));
        return objectMapper;
    }
}
