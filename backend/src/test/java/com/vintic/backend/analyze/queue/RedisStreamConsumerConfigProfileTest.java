package com.vintic.backend.analyze.queue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// RedisStreamConsumerConfig에 추가한 @ConditionalOnProperty(analysis.stream.consumer.enabled)의
// 존재/부재 여부만 검증한다. StringRedisTemplate/AnalysisTaskConsumer는 실제 Redis/JPA 의존성
// 없이 mock으로 대체한다 - 이 테스트의 관심사는 "조건에 따라 빈이 등록되는가"이고,
// AnalysisTaskConsumer의 비즈니스 로직(기존 Redis Streams 처리)은 대상이 아니다.
class RedisStreamConsumerConfigProfileTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(RedisStreamConsumerConfig.class)
            .withBean(StringRedisTemplate.class, this::mockRedisTemplate)
            .withBean(AnalysisStreamProperties.class, AnalysisStreamProperties::new)
            .withBean(AnalysisTaskConsumer.class, () -> mock(AnalysisTaskConsumer.class));

    private StringRedisTemplate mockRedisTemplate() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        when(template.getConnectionFactory()).thenReturn(mock(RedisConnectionFactory.class));
        return template;
    }

    @Test
    void 프로퍼티가_없으면_기존과_동일하게_빈이_등록된다() {
        contextRunner.run(context ->
                assertThat(context).hasSingleBean(RedisStreamConsumerConfig.class));
    }

    @Test
    void enabled_false면_빈이_등록되지_않는다() {
        contextRunner
                .withPropertyValues("analysis.stream.consumer.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(RedisStreamConsumerConfig.class));
    }

    @Test
    void enabled_true면_빈이_등록된다() {
        contextRunner
                .withPropertyValues("analysis.stream.consumer.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(RedisStreamConsumerConfig.class));
    }
}
