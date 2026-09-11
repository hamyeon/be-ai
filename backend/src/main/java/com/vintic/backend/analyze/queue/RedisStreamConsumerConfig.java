package com.vintic.backend.analyze.queue;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

// 애플리케이션 기동 시 Consumer Group을 만들고(없으면), AnalysisTaskConsumer를 Redis Stream에
// 구독시킨다. 지금은 별도 Worker 서버가 아니라 같은 Spring 애플리케이션 안의 백그라운드 컴포넌트다.
//
// analysis.stream.consumer.enabled가 없으면(matchIfMissing=true) 지금까지와 동일하게 켜진다 -
// local/dev/prod는 이 프로퍼티를 건드리지 않아 기존 동작이 그대로 유지된다. experiment-api/
// experiment-worker 프로필에서만 false로 꺼서, SQS 기반 파이프라인과 기존 Redis Streams
// 파이프라인이 같은 프로세스에서 동시에 돌지 않게 격리한다. 비즈니스 로직(AnalysisTaskConsumer)은
// 손대지 않는다.
@Component
@ConditionalOnProperty(prefix = "analysis.stream.consumer", name = "enabled", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class RedisStreamConsumerConfig {

    private final StringRedisTemplate redisTemplate;
    private final AnalysisStreamProperties properties;
    private final AnalysisTaskConsumer analysisTaskConsumer;

    private StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;

    @PostConstruct
    public void start() {
        createConsumerGroupIfAbsent();

        StreamMessageListenerContainer.StreamMessageListenerContainerOptions<String, MapRecord<String, String, String>> options =
                StreamMessageListenerContainer.StreamMessageListenerContainerOptions.builder()
                        .pollTimeout(Duration.ofSeconds(2))
                        .build();

        container = StreamMessageListenerContainer.create(
                Objects.requireNonNull(redisTemplate.getConnectionFactory()), options
        );

        // 인스턴스마다 고유해야 하는 Consumer 이름 (여러 인스턴스로 늘어나도 서로 겹치지 않도록)
        String consumerName = properties.getConsumerPrefix() + "-" + UUID.randomUUID();
        log.info(
                "Redis Stream Consumer 시작 - stream={}, group={}, consumer={}",
                properties.getKey(), properties.getGroup(), consumerName
        );

        container.receive(
                Consumer.from(properties.getGroup(), consumerName),
                StreamOffset.create(properties.getKey(), ReadOffset.lastConsumed()),
                analysisTaskConsumer
        );

        container.start();
    }

    @PreDestroy
    public void stop() {
        if (container != null) {
            container.stop();
        }
    }

    private void createConsumerGroupIfAbsent() {
        try {
            redisTemplate.opsForStream().createGroup(properties.getKey(), ReadOffset.from("0"), properties.getGroup());
        } catch (Exception e) {
            // 이미 Consumer Group이 존재하면 Redis가 BUSYGROUP 에러를 던진다 - 정상 상황이므로 무시한다.
            log.debug("Consumer Group 생성을 건너뜁니다(이미 존재하거나 생성 불가): {}", e.getMessage());
        }
    }
}
