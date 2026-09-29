package com.vintic.backend.analyze.queue;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.StreamMessageListenerContainer.StreamReadRequest;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.IntStream;

// 애플리케이션 기동 시 Consumer Group을 만들고(없으면), AnalysisTaskConsumer를 Redis Stream에
// 구독시킨다. 지금은 별도 Worker 서버가 아니라 같은 Spring 애플리케이션 안의 백그라운드 컴포넌트다.
//
// #106에서 바꾼 것:
// - 구독이 예외 한 번에 죽지 않게 했다. Spring Data Redis의 기본값(cancelOnError = 항상 true)은
//   Redis 읽기 실패나 리스너 예외가 한 번만 나도 구독을 영구히 취소한다(3.5.11 StreamPollTask 확인).
//   Redis가 잠깐 재시작되면 서버는 멀쩡한데 분석만 전부 QUEUED에서 멈추고, 로그 한 줄 말고는 신호가 없었다.
// - analysis.stream.concurrency만큼 Consumer를 띄워 동시에 처리한다.
@Component
@RequiredArgsConstructor
@Slf4j
public class RedisStreamConsumerConfig {

    private final StringRedisTemplate redisTemplate;
    private final AnalysisStreamProperties properties;
    private final AnalysisTaskConsumer analysisTaskConsumer;

    // 인스턴스마다 고유해야 하는 Consumer 이름의 앞부분 (여러 인스턴스로 늘어나도 서로 겹치지 않도록)
    private final String instanceId = UUID.randomUUID().toString();

    private StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;

    @PostConstruct
    public void start() {
        createConsumerGroupIfAbsent();

        StreamMessageListenerContainer.StreamMessageListenerContainerOptions<String, MapRecord<String, String, String>> options =
                StreamMessageListenerContainer.StreamMessageListenerContainerOptions.builder()
                        .pollTimeout(Duration.ofSeconds(2))
                        // 한 번에 한 건만 가져온다. 여러 건을 가져가면 그 Consumer가 순서대로 처리하는 동안
                        // 다른 Consumer는 놀게 되어 동시 처리가 무의미해진다.
                        .batchSize(1)
                        .build();

        container = StreamMessageListenerContainer.create(
                Objects.requireNonNull(redisTemplate.getConnectionFactory()), options
        );

        StreamPollErrorHandler errorHandler = new StreamPollErrorHandler(this::createConsumerGroupIfAbsent);
        List<String> consumerNames = consumerNames(properties.getConsumerPrefix(), instanceId, properties.getConcurrency());
        log.info(
                "Redis Stream Consumer 시작 - stream={}, group={}, consumers={}",
                properties.getKey(), properties.getGroup(), consumerNames
        );

        // 기본 executor(SimpleAsyncTaskExecutor)가 구독마다 스레드를 하나씩 준다.
        consumerNames.forEach(consumerName -> container.register(
                readRequest(properties.getKey(), properties.getGroup(), consumerName, errorHandler),
                analysisTaskConsumer
        ));

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

    static List<String> consumerNames(String prefix, String instanceId, int concurrency) {
        return IntStream.range(0, Math.max(1, concurrency))
                .mapToObj(index -> "%s-%s-%d".formatted(prefix, instanceId, index))
                .toList();
    }

    static StreamReadRequest<String> readRequest(String streamKey, String group, String consumerName,
                                                 StreamPollErrorHandler errorHandler) {
        return StreamReadRequest.builder(StreamOffset.create(streamKey, ReadOffset.lastConsumed()))
                .consumer(Consumer.from(group, consumerName))
                // ACK는 AnalysisTaskConsumer가 DB 저장 성공 후에만 직접 한다.
                .autoAcknowledge(false)
                // 어떤 예외에도 구독을 유지한다. 실패한 메시지는 PEL에 남고 AnalysisStreamRecoveryScheduler가 회수한다.
                .cancelOnError(error -> false)
                .errorHandler(errorHandler)
                .build();
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
