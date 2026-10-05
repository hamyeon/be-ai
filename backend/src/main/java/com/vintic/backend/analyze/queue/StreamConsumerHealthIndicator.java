package com.vintic.backend.analyze.queue;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

// Worker EC2 배포의 actuator 자동 배포 게이트(scripts/aws/deploy.sh)가 확인하는 health indicator.
// "consumer 프로세스가 실제로 돌고 있는가"를 두 가지로 직접 확인한다 - (1) 이 프로세스 자신의
// StreamMessageListenerContainer가 running인지(RedisStreamConsumerConfig.isRunning(), 다른
// 프로세스/죽은 프로세스의 흔적일 수 있는 Redis 쪽 레지스트리가 아니라 JVM 내부 상태),
// (2) Redis 연결 자체가 살아있는지(PING). 이 두 가지를 넘어 "실제로 메시지를 처리하는가"는 이
// health check의 책임이 아니다 - 배포 스크립트가 별도로 실제 analyze 요청 1건을 흘려보내
// COMPLETED까지 확인한다(scripts/aws/smoke-analyze.sh, 자동 배포 게이트와 분리된 이유는 Vision API
// 호출 비용(기본 Claude) 때문).
//
// consumer.enabled(analysis.stream.consumer.enabled)가 true인 프로세스에만 등록한다 - API
// 프로세스는 애초에 소비자를 띄우지 않으므로 이 indicator가 있으면 항상 DOWN을 내 API 자신의
// /actuator/health를 무의미하게 실패시킨다.
@Component("streamConsumer")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "analysis.stream.consumer", name = "enabled", havingValue = "true")
public class StreamConsumerHealthIndicator implements HealthIndicator {

    private final RedisStreamConsumerConfig consumerConfig;
    private final StringRedisTemplate redisTemplate;

    @Override
    public Health health() {
        if (!consumerConfig.isRunning()) {
            return Health.down().withDetail("reason", "StreamMessageListenerContainer가 running 상태가 아닙니다.").build();
        }

        try {
            String pong = redisTemplate.getConnectionFactory().getConnection().ping();
            if (!"PONG".equalsIgnoreCase(pong)) {
                return Health.down().withDetail("reason", "Redis PING 응답이 올바르지 않습니다: " + pong).build();
            }
        } catch (RuntimeException e) {
            return Health.down().withDetail("reason", "Redis 연결 확인 중 오류: " + e.getMessage()).build();
        }

        return Health.up().build();
    }
}
