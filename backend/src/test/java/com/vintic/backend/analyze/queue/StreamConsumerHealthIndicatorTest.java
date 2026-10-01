package com.vintic.backend.analyze.queue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StreamConsumerHealthIndicatorTest {

    @Mock
    private RedisStreamConsumerConfig consumerConfig;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private RedisConnectionFactory connectionFactory;

    @Mock
    private RedisConnection connection;

    private StreamConsumerHealthIndicator newIndicator() {
        return new StreamConsumerHealthIndicator(consumerConfig, redisTemplate);
    }

    @Test
    void 컨테이너가_running이_아니면_DOWN이다() {
        when(consumerConfig.isRunning()).thenReturn(false);

        Health health = newIndicator().health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void 컨테이너가_running이고_Redis_PING이_성공하면_UP이다() {
        when(consumerConfig.isRunning()).thenReturn(true);
        when(redisTemplate.getConnectionFactory()).thenReturn(connectionFactory);
        when(connectionFactory.getConnection()).thenReturn(connection);
        when(connection.ping()).thenReturn("PONG");

        Health health = newIndicator().health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void 컨테이너는_running이지만_Redis_연결이_끊기면_DOWN이다() {
        when(consumerConfig.isRunning()).thenReturn(true);
        when(redisTemplate.getConnectionFactory()).thenReturn(connectionFactory);
        when(connectionFactory.getConnection()).thenThrow(new RuntimeException("연결 거부"));

        Health health = newIndicator().health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    }
}
