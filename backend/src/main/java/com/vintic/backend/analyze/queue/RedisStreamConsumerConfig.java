package com.vintic.backend.analyze.queue;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
// enabled(analysis.stream.consumer.enabled, 기본 false): API/Worker EC2 분리 배포에서 어느 프로세스가
// 소비자를 실행할지 가르는 게이트다. 기본값은 false지만 application-local.yml/application-dev.yml이
// true로 올려 기존 단일 프로세스(로컬 docker-compose, dev) 동작은 그대로 유지한다 - 배포 환경에서만
// application-api.yml이 false로, application-worker.yml이 true로 명시적으로 가른다.
@Component
@RequiredArgsConstructor
@Slf4j
public class RedisStreamConsumerConfig {

    private final StringRedisTemplate redisTemplate;
    private final AnalysisStreamProperties properties;
    private final AnalysisTaskConsumer analysisTaskConsumer;

    @Value("${analysis.stream.consumer.enabled:false}")
    private boolean enabled;

    private StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;

    // Worker 배포의 actuator health indicator(StreamConsumerHealthIndicator)가 쓴다 - Redis
    // 쪽 레지스트리(XINFO GROUPS)가 아니라 이 프로세스 자신이 실제로 컨테이너를 돌리고 있는지를
    // 직접 반영한다(죽은 프로세스의 흔적이 한동안 Redis에 남아있는 문제를 피한다).
    public boolean isRunning() {
        return container != null && container.isRunning();
    }

    @PostConstruct
    public void start() {
        if (!enabled) {
            log.info("analysis.stream.consumer.enabled=false - 이 프로세스는 Redis Stream Consumer를 시작하지 않습니다.");
            return;
        }
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

    // container.stop()은 새 poll을 멈추지만, 이미 시작된 onMessage() 호출을 기다려주지
    // 않는다(stop(Runnable) 콜백도 마찬가지 - 실측으로 확인함, 둘 다 진행 중인 호출과 무관하게
    // 곧바로 반환/콜백이 온다). "진행 중인 작업을 마칠 시간을 준다"는 요구사항을 만족시키려면
    // AnalysisTaskConsumer의 in-flight 카운터가 0이 될 때까지 여기서 직접 기다려야 한다.
    @PreDestroy
    public void stop() {
        if (container == null) {
            return;
        }
        container.stop();
        waitForInFlightWorkToFinish();
    }

    private void waitForInFlightWorkToFinish() {
        long deadline = System.currentTimeMillis() + properties.getShutdownGracePeriodMs();
        while (analysisTaskConsumer.getInFlightCount() > 0 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        int remaining = analysisTaskConsumer.getInFlightCount();
        if (remaining > 0) {
            log.warn(
                    "종료 유예 시간({}ms) 안에 진행 중인 작업 {}건이 끝나지 않았습니다 - ACK되지 않은 채로 남아 "
                            + "다음 Worker가 회수합니다.",
                    properties.getShutdownGracePeriodMs(), remaining
            );
        } else {
            log.info("진행 중이던 작업이 모두 끝난 뒤 종료합니다.");
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
