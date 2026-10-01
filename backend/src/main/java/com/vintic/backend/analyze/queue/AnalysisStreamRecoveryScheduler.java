package com.vintic.backend.analyze.queue;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

// PEL(pending entries list)에 minIdleTime 이상 오래 남은 메시지를 주기적으로 회수(XCLAIM)해
// AnalysisTaskConsumer.processReclaimed()로 재처리시킨다. 인스턴스마다 독립적으로 돈다(리더
// 선출 없음) - 두 인스턴스의 스캔이 같은 idle 엔트리를 동시에 노려도 XCLAIM이 idle-time을
// Redis 서버에서 다시 검증하므로 한쪽만 실제로 회수에 성공한다(claim.isEmpty()로 판별).
//
// Spring Data Redis 3.5.x의 StreamOperations에는 XAUTOCLAIM 래퍼가 없다(XCLAIM/XPENDING만
// 제공) - 그래서 XPENDING으로 후보를 훑고(IDLE 필터는 클라이언트에서 elapsedTimeSinceLastDelivery로
// 적용) 실제로 idle 기준을 넘긴 것만 XCLAIM으로 회수하는 두 단계로 동일한 효과를 낸다.
//
// minIdleTimeMs 기본값의 근거는 AnalysisStreamRecoveryProperties 주석 참고 - 휴리스틱이 아니라
// analysis.vision.overall-timeout-ms(Vision 처리 강제 상한)에서 도출한 값이다.
@Component
@RequiredArgsConstructor
@Slf4j
public class AnalysisStreamRecoveryScheduler {

    private final StringRedisTemplate redisTemplate;
    private final AnalysisStreamProperties streamProperties;
    private final AnalysisStreamRecoveryProperties recoveryProperties;
    private final AnalysisTaskConsumer analysisTaskConsumer;
    private final AnalysisStreamMetrics metrics;

    private final String consumerName = "recovery-" + UUID.randomUUID();
    private String scanCursor;

    // 종료 신호(@PreDestroy) 이후에는 새 스캔 사이클을 시작하지 않는다. 이미 시작된 processReclaimed
    // 호출은 AnalysisTaskConsumer의 in-flight 추적으로 별도로 보호된다(RedisStreamConsumerConfig 참고).
    private volatile boolean shuttingDown;

    @Scheduled(fixedDelayString = "${analysis.stream.recovery.scan-interval-ms:30000}")
    public void scanAndReclaim() {
        if (shuttingDown || !recoveryProperties.isEnabled()) {
            return;
        }
        refreshPendingMetrics();
        try {
            scanOnce();
        } catch (RuntimeException e) {
            metrics.recordRedisError("xclaim");
            log.error("PEL 회수 스캔 중 오류가 발생했습니다.", e);
        }
    }

    @PreDestroy
    public void stopAcceptingNewScans() {
        shuttingDown = true;
    }

    // XPENDING 조회 자체가 실패해도(연결 장애 등) 회수 로직과 분리된 오류로 남긴다 - 실패하면
    // 게이지는 갱신하지 않고 마지막 성공값을 유지한다(AnalysisStreamMetrics 참고, last_check_age_ms
    // 로 신선도를 판단).
    private void refreshPendingMetrics() {
        try {
            PendingMessagesSummary summary = redisTemplate.opsForStream()
                    .pending(streamProperties.getKey(), streamProperties.getGroup());
            long oldestIdleMs = 0;
            PendingMessages oldest = redisTemplate.opsForStream()
                    .pending(streamProperties.getKey(), streamProperties.getGroup(), Range.unbounded(), 1);
            if (!oldest.isEmpty()) {
                oldestIdleMs = oldest.get(0).getElapsedTimeSinceLastDelivery().toMillis();
            }
            metrics.recordPendingSnapshot(summary.getTotalPendingMessages(), oldestIdleMs);
        } catch (RuntimeException e) {
            metrics.recordRedisError("xpending");
            log.error("PEL 상태 조회(XPENDING)에 실패했습니다 - pending 게이지는 마지막 성공값으로 남습니다.", e);
        }
    }

    private void scanOnce() {
        Range<String> range = scanCursor == null
                ? Range.unbounded()
                : Range.of(Range.Bound.exclusive(scanCursor), Range.Bound.unbounded());

        PendingMessages pending = redisTemplate.opsForStream().pending(
                streamProperties.getKey(), streamProperties.getGroup(), range, recoveryProperties.getBatchSize()
        );

        if (pending.isEmpty()) {
            scanCursor = null; // 끝까지 훑었으니 다음 스캔은 처음부터 다시(회수 못한 것도 계속 후보로 남음)
            return;
        }

        Duration minIdleTime = Duration.ofMillis(recoveryProperties.getMinIdleTimeMs());
        int reclaimed = 0;
        for (PendingMessage candidate : pending) {
            scanCursor = candidate.getIdAsString();
            if (candidate.getElapsedTimeSinceLastDelivery().compareTo(minIdleTime) < 0) {
                continue; // 아직 idle 기준을 안 넘김 - 정상 처리 중일 수 있음, 회수하지 않는다
            }

            List<MapRecord<String, String, String>> claimed = redisTemplate.<String, String>opsForStream().claim(
                    streamProperties.getKey(), streamProperties.getGroup(), consumerName,
                    minIdleTime, candidate.getId()
            );
            if (claimed.isEmpty()) {
                continue; // 다른 인스턴스가 먼저 회수함(XCLAIM이 idle-time을 서버에서 재검증)
            }

            reclaimed++;
            metrics.recordReclaimed();
            // 이번 회수 자체가 새로운 배달 시도이므로 +1 - candidate.getTotalDeliveryCount()는
            // 이번 XCLAIM 이전까지 배달된 횟수다(AnalysisVisionProcessingProperties.maxDeliveryAttempts 참고).
            analysisTaskConsumer.processReclaimed(claimed.get(0), candidate.getTotalDeliveryCount() + 1);
        }

        if (reclaimed > 0) {
            log.info("PEL 회수를 시도했습니다. consumer={}, reclaimed={}", consumerName, reclaimed);
        }
    }
}
