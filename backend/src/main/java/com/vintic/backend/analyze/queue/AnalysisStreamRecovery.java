package com.vintic.backend.analyze.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.analyze.domain.ProductAnalysisSession;
import com.vintic.backend.analyze.domain.ProductAnalysisSessionRepository;
import com.vintic.backend.analyze.service.AnalysisFailureRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.StreamInfo;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

// ACK되지 못하고 PEL(Pending Entries List)에 남은 분석 메시지를 회수한다(#106).
//
// Consumer는 새 메시지만 읽는다(ReadOffset.lastConsumed). 처리 도중 서버가 재시작되거나 DB 저장이
// 실패해 ACK하지 못한 메시지는 아무도 다시 읽지 않아서, 세션이 QUEUED/VISION_PROCESSING에 영원히 남고
// 프론트는 끝나지 않는 폴링을 계속했다. 통합 기간에는 배포가 잦아 이 경우가 흔하다.
//
// pending-idle-timeout 넘게 방치된 메시지를 XCLAIM으로 가져와 세션 상태를 보고 정리한다.
//   QUEUED            -> 분석을 시작도 못 했다. 새 메시지로 다시 넣는다(Vision 비용이 아직 안 들었다)
//   VISION_PROCESSING -> 분석 도중 멈췄다. 실패로 기록한다(자동 재시도는 유료 호출을 반복할 수 있어 하지 않는다)
//   그 밖의 상태       -> 이미 끝났는데 ACK만 못 했다. ACK만 한다
//   세션 없음·파싱 불가 -> 다시 시도해도 소용없다. ACK하고 버린다
//
// 인스턴스가 여러 대면 모두 이 작업을 돌린다. XCLAIM은 min-idle 조건을 다시 확인하므로 먼저 가져간
// 인스턴스만 성공하고, 나머지는 빈 결과를 받는다 - 같은 메시지를 두 번 정리하지 않는다.
@Component
@RequiredArgsConstructor
@Slf4j
public class AnalysisStreamRecovery {

    static final String TIMEOUT_FAILURE_MESSAGE =
            "분석이 제한 시간 안에 끝나지 않아 중단했습니다(서버 재시작 등). 사진을 다시 올려 주세요.";

    private final StringRedisTemplate redisTemplate;
    private final AnalysisStreamProperties properties;
    private final ProductAnalysisSessionRepository sessionRepository;
    private final AnalysisFailureRecorder failureRecorder;
    private final AnalysisTaskProducer producer;
    private final ObjectMapper objectMapper;
    private final RedisStreamConsumerConfig consumerConfig;

    @Scheduled(
            initialDelayString = "${analysis.stream.recovery.fixed-delay-ms:60000}",
            fixedDelayString = "${analysis.stream.recovery.fixed-delay-ms:60000}")
    public void recover() {
        // 스케줄러 스레드는 경매 종료 등 다른 배치와 같이 쓴다. 여기서 예외가 새지 않게 한다.
        try {
            reclaimStalePendingMessages();
            removeIdleConsumers();
        } catch (RuntimeException e) {
            log.warn("분석 스트림 회수 작업 실패 - 다음 주기에 다시 시도합니다. 원인: {}", e.getMessage());
        }
    }

    int reclaimStalePendingMessages() {
        StreamOperations<String, Object, Object> streams = redisTemplate.opsForStream();
        AnalysisStreamProperties.Recovery recovery = properties.getRecovery();
        Duration idleTimeout = recovery.getPendingIdleTimeout();

        PendingMessages pending = streams.pending(
                properties.getKey(), properties.getGroup(), Range.unbounded(), recovery.getBatchSize());

        int resolved = 0;
        for (PendingMessage message : pending) {
            if (message.getElapsedTimeSinceLastDelivery().compareTo(idleTimeout) < 0) {
                continue;
            }
            List<MapRecord<String, Object, Object>> claimed = streams.claim(
                    properties.getKey(), properties.getGroup(), recoveryConsumerName(), idleTimeout, message.getId());
            // claimed가 비어 있으면 다른 인스턴스가 먼저 가져갔거나, 트리밍으로 엔트리가 이미 지워진 것이다.
            for (MapRecord<String, Object, Object> record : claimed) {
                try {
                    resolve(record, message.getElapsedTimeSinceLastDelivery());
                    resolved++;
                } catch (RuntimeException e) {
                    // ACK하지 않았으므로 PEL에 남는다. 이번에 가져온 시점부터 다시 방치 시간을 세어 다음에 또 본다.
                    log.warn("분석 메시지 회수 실패 - 다음 주기에 다시 봅니다. recordId={}, 원인: {}",
                            record.getId(), e.getMessage());
                }
            }
        }
        return resolved;
    }

    private void resolve(MapRecord<String, Object, Object> record, Duration idle) {
        Optional<AnalysisTaskMessage> parsed = parse(record);
        if (parsed.isEmpty()) {
            log.error("파싱할 수 없는 분석 메시지를 버립니다. recordId={}", record.getId());
            acknowledge(record);
            return;
        }
        AnalysisTaskMessage message = parsed.get();

        ProductAnalysisSession session = sessionRepository.findById(message.analysisId()).orElse(null);
        if (session == null) {
            log.warn("세션이 없는 분석 메시지를 버립니다. analysisId={}, recordId={}", message.analysisId(), record.getId());
            acknowledge(record);
            return;
        }

        switch (session.getStatus()) {
            case QUEUED -> {
                // 새 엔트리로 넣고 옛 엔트리를 ACK한다. 순서를 바꾸면 넣기 실패 시 메시지를 잃는다.
                producer.enqueue(message);
                log.warn("분석을 시작하지 못한 메시지를 다시 넣었습니다. analysisId={}, 방치 시간={}s",
                        session.getId(), idle.toSeconds());
            }
            case VISION_PROCESSING -> {
                failureRecorder.recordVisionFailure(session.getId(), TIMEOUT_FAILURE_MESSAGE);
                log.warn("처리 도중 멈춘 분석을 실패로 정리했습니다. analysisId={}, 방치 시간={}s",
                        session.getId(), idle.toSeconds());
            }
            default -> log.info("이미 끝난 분석의 미처리 메시지를 정리합니다. analysisId={}, status={}",
                    session.getId(), session.getStatus());
        }
        acknowledge(record);
    }

    // 미처리 메시지가 없고 오래 조용한 Consumer를 그룹에서 지운다. 이 인스턴스의 Consumer는 건드리지 않는다.
    int removeIdleConsumers() {
        StreamOperations<String, Object, Object> streams = redisTemplate.opsForStream();
        StreamInfo.XInfoConsumers consumers = streams.consumers(properties.getKey(), properties.getGroup());
        long idleLimitMs = properties.getRecovery().getIdleConsumerTimeout().toMillis();
        String ownPrefix = consumerConfig.instanceConsumerPrefix();

        int removed = 0;
        for (StreamInfo.XInfoConsumer consumer : consumers) {
            boolean own = consumer.consumerName().startsWith(ownPrefix);
            if (!own && consumer.pendingCount() == 0 && consumer.idleTimeMs() > idleLimitMs) {
                streams.deleteConsumer(properties.getKey(), Consumer.from(properties.getGroup(), consumer.consumerName()));
                removed++;
            }
        }
        if (removed > 0) {
            log.info("오래 쓰이지 않은 분석 Consumer {}개를 그룹에서 지웠습니다.", removed);
        }
        return removed;
    }

    private String recoveryConsumerName() {
        return consumerConfig.instanceConsumerPrefix() + "-recovery";
    }

    private Optional<AnalysisTaskMessage> parse(MapRecord<String, Object, Object> record) {
        try {
            Object payload = record.getValue().get("payload");
            return Optional.of(objectMapper.readValue(String.valueOf(payload), AnalysisTaskMessage.class));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private void acknowledge(MapRecord<String, Object, Object> record) {
        redisTemplate.opsForStream().acknowledge(properties.getKey(), properties.getGroup(), record.getId());
    }
}
