package com.vintic.backend.analyze.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.ai.vision.dto.VisionAnalysisRequest;
import com.vintic.backend.ai.vision.dto.VisionAnalysisResult;
import com.vintic.backend.ai.vision.service.VisionAnalysisService;
import com.vintic.backend.analyze.domain.VisionAttemptOutcome;
import com.vintic.backend.analyze.domain.VisionFailureAttemptResult;
import com.vintic.backend.analyze.service.AnalysisFailureRecorder;
import com.vintic.backend.analyze.service.VisionAttemptCoordinator;
import com.vintic.backend.analyze.service.VisionFailureStreamRecorder;
import com.vintic.backend.common.exception.AiApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

// Redis Stream에서 분석 작업 메시지를 받아 Vision 분석을 실행하는 Consumer.
// DB 저장(완료/실패)이 성공한 뒤에만 XACK한다 - 저장 자체가 실패하면 ack하지 않고
// 미처리 메시지(Pending Entries List)로 남겨 나중에 재처리할 수 있게 한다.
//
// claim/complete/fail은 모두 VisionAttemptCoordinator/AnalysisFailureRecorder가 짧은
// 트랜잭션 + fencing token으로 원자적으로 처리한다(ProductAnalysisSession 참고). 이 클래스는
// 그 결과(VisionAttemptOutcome)로 ACK 여부만 결정한다:
//   COMMITTED/ALREADY_FINALIZED -> ACK(단, VISION_FAILED면 실패 Stream 발행까지 확인한 뒤),
//   OWNERSHIP_LOST -> ACK 안 함(다른 시도가 소유권을 가짐).
//
// 재시도: OpenAiVisionClient가 이미 단계별로 429/5xx/네트워크 오류를 최대 5회 재시도하므로,
// 여기서는 그 재시도를 반복하지 않는다. VisionFailureClassifier가 "재시도 가치가 있는지"를
// 판정하고, 재시도 가치가 있고 배달 횟수가 아직 상한(maxDeliveryAttempts)을 안 넘었으면 그냥
// ACK하지 않고 끝낸다 - AnalysisStreamRecoveryScheduler가 minIdleTime 간격으로 다시 넘겨주는
// 것 자체가 재시도다. 재시도 가치가 없거나 배달 횟수를 넘겼을 때만 최종 실패로 기록한다.
//
// AnalysisStreamRecoveryScheduler가 PEL에서 회수한 메시지는 processReclaimed()로 들어오는데,
// claim 대상 상태가 다를 뿐(QUEUED만 vs QUEUED/VISION_PROCESSING) Vision 호출과 완료/실패 처리는
// processVisionAndFinish()를 그대로 공유한다.
//
// inFlight: RedisStreamConsumerConfig가 종료 시 이 카운터가 0이 될 때까지(상한 시간 안에서)
// 기다려 진행 중인 작업이 끝날 시간을 준다 - StreamMessageListenerContainer.stop()/stop(Runnable)
// 자체는 진행 중인 onMessage() 호출을 기다려주지 않는다는 것을 실측으로 확인했다.
@Component
@RequiredArgsConstructor
@Slf4j
public class AnalysisTaskConsumer implements StreamListener<String, MapRecord<String, String, String>> {

    private static final String PAYLOAD_FIELD = "payload";
    private static final int FAILURE_MESSAGE_MAX_LENGTH = 1000;

    private final VisionAnalysisService visionAnalysisService;
    private final VisionAttemptCoordinator coordinator;
    private final AnalysisFailureRecorder failureRecorder;
    private final VisionFailureStreamRecorder failureStreamRecorder;
    private final VisionFailureStreamProducer failureStreamProducer;
    private final VisionFailureClassifier failureClassifier;
    private final AnalysisStreamMetrics metrics;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;
    private final AnalysisStreamProperties properties;
    private final AnalysisVisionProcessingProperties visionProperties;
    private final ExecutorService visionAnalysisExecutor;

    private final AtomicInteger inFlight = new AtomicInteger();

    public int getInFlightCount() {
        return inFlight.get();
    }

    @Override
    public void onMessage(MapRecord<String, String, String> record) {
        inFlight.incrementAndGet();
        try {
            AnalysisTaskMessage message = parseMessage(record);
            if (message == null) {
                return; // 메시지 자체가 파싱이 안 됨 - ack 안 하고 미처리로 남김
            }

            String token = UUID.randomUUID().toString();
            VisionAttemptOutcome claimOutcome;
            try {
                claimOutcome = coordinator.claim(message.analysisId(), token);
            } catch (RuntimeException e) {
                log.error("VISION_PROCESSING 상태 저장에 실패했습니다. analysisId={}", message.analysisId(), e);
                return; // DB 저장 자체가 실패 - ack 안 함, 미처리로 남김
            }

            switch (claimOutcome) {
                case ALREADY_FINALIZED -> ackAfterEnsuringFailurePublished(record, message.analysisId());
                case OWNERSHIP_LOST ->
                        log.warn("신규 배달인데 Vision 처리 소유권을 확보하지 못했습니다. analysisId={}", message.analysisId());
                case COMMITTED ->
                        processVisionAndFinish(record, message.analysisId(), message.visionImageUrls(), token, 1L);
            }
        } finally {
            inFlight.decrementAndGet();
        }
    }

    // PEL(pending entries list)에서 회수(XCLAIM)된 메시지 전용 진입점. AnalysisStreamRecoveryScheduler가
    // 직접 호출한다 - Redis 재배달이 아니라 스케줄러가 이미 XCLAIM으로 소유권을 가져온 뒤 넘겨주는
    // 메시지다. QUEUED뿐 아니라 VISION_PROCESSING(이전 Worker가 죽었을 가능성)도 회수 대상이다.
    // deliveryCount는 이번 회수를 포함해 이 메시지가 총 몇 번째로 배달됐는지다(Redis가 추적) -
    // 재시도 가능한 오류라도 이 값이 상한을 넘으면 최종 실패로 기록한다.
    public void processReclaimed(MapRecord<String, String, String> record, long deliveryCount) {
        inFlight.incrementAndGet();
        try {
            AnalysisTaskMessage message = parseMessage(record);
            if (message == null) {
                log.error("회수된 메시지를 파싱하지 못했습니다. recordId={}", record.getId());
                return; // ack 안 함 - 다음 스캔에서 다시 회수 시도(파싱 실패는 상태와 무관)
            }

            String token = UUID.randomUUID().toString();
            VisionAttemptOutcome reclaimOutcome;
            try {
                reclaimOutcome = coordinator.reclaim(message.analysisId(), token);
            } catch (RuntimeException e) {
                log.error("회수한 세션의 소유권 갱신에 실패했습니다. analysisId={}", message.analysisId(), e);
                return;
            }

            switch (reclaimOutcome) {
                case ALREADY_FINALIZED -> ackAfterEnsuringFailurePublished(record, message.analysisId());
                case OWNERSHIP_LOST ->
                        log.warn("회수 시점에 이미 다른 시도가 소유권을 가지고 있습니다. analysisId={}", message.analysisId());
                case COMMITTED -> {
                    log.info("PEL에서 회수해 Vision 처리를 재시도합니다. analysisId={}, deliveryCount={}",
                            message.analysisId(), deliveryCount);
                    processVisionAndFinish(record, message.analysisId(), message.visionImageUrls(), token, deliveryCount);
                }
            }
        } finally {
            inFlight.decrementAndGet();
        }
    }

    private void processVisionAndFinish(
            MapRecord<String, String, String> record, Long sessionId, List<String> imageUrls,
            String token, long deliveryCount
    ) {
        VisionAnalysisResult result;
        try {
            result = callVisionWithDeadline(imageUrls, sessionId);
        } catch (RuntimeException visionError) {
            handleVisionFailure(record, sessionId, token, deliveryCount, visionError);
            return;
        }

        if (tryCompleteVision(sessionId, token, result)) {
            acknowledge(record);
        }
    }

    // 재시도 가치가 있고(VisionFailureClassifier) 진짜 Vision 실패 횟수가 아직 상한을 안 넘었으면
    // 아무것도 하지 않고 끝낸다 - ACK하지 않은 채로 두면 AnalysisStreamRecoveryScheduler가 나중에
    // 다시 넘겨준다(이것이 이 설계의 "재시도"다, 클래스 상단 주석 참고). 그 외에는 최종 실패로
    // 기록한다. deliveryCount(Redis 배달 횟수)는 로그용일 뿐 재시도 상한 판정에는 쓰지 않는다 -
    // executor 포화로 인한 재전달도 함께 세기 때문에, 배달 횟수를 상한 판정에 쓰면 "여유가 생겨
    // 실제로 Vision을 처음 호출했는데 곧바로 최종 실패로 확정되는" 문제가 생긴다.
    private void handleVisionFailure(
            MapRecord<String, String, String> record, Long sessionId, String token,
            long deliveryCount, RuntimeException visionError
    ) {
        // executor 포화는 Vision을 아예 시도조차 못한 순수 로컬 용량 문제다 - 재시도 상한과
        // 무관하게 항상 재시도한다(DB에도 손대지 않는다 - 아래 카운트를 소모하지 않음).
        if (failureClassifier.isLocalOverload(visionError)) {
            log.warn("Vision 처리 executor가 가득 차 있어 이번 시도를 시작하지 못했습니다 - 재시도 상한과 무관하게 재시도합니다. "
                    + "analysisId={}, deliveryCount={}", sessionId, deliveryCount);
            return;
        }

        if (!failureClassifier.isRetryable(visionError)) {
            if (tryRecordVisionFailure(sessionId, token, truncate(visionError.getMessage()))) {
                acknowledge(record);
            }
            return;
        }

        VisionFailureAttemptResult attemptResult = tryIncrementFailureAttempt(sessionId, token);
        if (attemptResult == null) {
            return; // DB 저장 자체가 실패 - ack 안 함, 미처리로 남김
        }
        switch (attemptResult.outcome()) {
            case OWNERSHIP_LOST ->
                    log.warn("Vision 실패 횟수 기록 시 소유권을 상실했습니다(다른 시도가 이미 재선점함). analysisId={}", sessionId);
            case ALREADY_FINALIZED -> ackAfterEnsuringFailurePublished(record, sessionId);
            case COMMITTED -> {
                if (attemptResult.attemptCount() < visionProperties.getMaxVisionFailureAttempts()) {
                    log.warn("일시 오류로 판단해 재시도를 위해 ACK하지 않습니다. analysisId={}, visionFailureAttemptCount={}, "
                            + "deliveryCount={}, error={}",
                            sessionId, attemptResult.attemptCount(), deliveryCount, visionError.getMessage());
                    return;
                }
                String rawMessage = "재시도 횟수 초과(" + attemptResult.attemptCount() + "회 시도): " + visionError.getMessage();
                if (tryRecordVisionFailure(sessionId, token, truncate(rawMessage))) {
                    acknowledge(record);
                }
            }
        }
    }

    private VisionFailureAttemptResult tryIncrementFailureAttempt(Long sessionId, String token) {
        try {
            return coordinator.incrementFailureAttempt(sessionId, token);
        } catch (RuntimeException e) {
            log.error("Vision 실패 횟수 기록에 실패했습니다. analysisId={}", sessionId, e);
            return null;
        }
    }

    // visionAnalysisService.analyze()는 3단계 x 재시도로 구성돼 상한이 없다(OpenAiVisionClient
    // 참고) - PEL 회수(minIdleTime)가 "처리에 상한이 있다"는 전제로 동작하려면 여기서 상한을
    // 걸어야 한다.
    //
    // 주의: 여기서 "타임아웃"은 이 메서드가 더 이상 기다리지 않고 실패 처리로 넘어간다는 뜻일
    // 뿐, 이미 제출된 시도를 즉시 멈추는 게 아니다. future.cancel(true)는 백오프 sleep() 중이면
    // 바로 끊지만(OpenAiVisionClient.sleep() 참고), RestTemplate의 블로킹 소켓 I/O는
    // Thread.interrupt()에 반응하지 않아 visionRestTemplate의 readTimeout(기본 30s)이 지나야
    // 스스로 풀린다 - 그 사이 해당 OpenAI 호출은 계속 나가고 executor 스레드도 계속 점유된다.
    // 이 시도의 결과(성공/실패 어느 쪽이든)는 아무도 다시 읽지 않으므로 DB에 쓰이는 일은 없다 -
    // 이미 실패로 확정하고 ACK한 뒤이기 때문이다. 스레드 점유가 누적되는 것 자체는
    // VisionExecutorConfig의 유한한 풀+큐(RejectedExecutionException으로 빠르게 실패)로 막는다.
    private VisionAnalysisResult callVisionWithDeadline(List<String> imageUrls, Long analysisId) {
        Future<VisionAnalysisResult> future;
        try {
            future = visionAnalysisExecutor.submit(
                    () -> visionAnalysisService.analyze(new VisionAnalysisRequest(imageUrls, analysisId))
            );
        } catch (RejectedExecutionException e) {
            throw new AiApiException(
                    "Vision 처리 executor가 가득 차 있어 이번 시도를 시작하지 못했습니다(로컬 과부하 - 재시도 대상).", e
            );
        }
        try {
            return future.get(visionProperties.getOverallTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new AiApiException(
                    "Vision 분석이 처리 상한(" + visionProperties.getOverallTimeoutMs() + "ms)을 넘겨 대기를 중단했습니다.", e
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiApiException("Vision 분석 대기 중 인터럽트되었습니다.", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeCause) {
                throw runtimeCause;
            }
            throw new AiApiException("Vision 분석 중 오류가 발생했습니다.", cause);
        }
    }

    private AnalysisTaskMessage parseMessage(MapRecord<String, String, String> record) {
        try {
            String payload = record.getValue().get(PAYLOAD_FIELD);
            return objectMapper.readValue(payload, AnalysisTaskMessage.class);
        } catch (Exception e) {
            log.error("분석 작업 메시지를 파싱하지 못했습니다. recordId={}", record.getId(), e);
            return null;
        }
    }

    private boolean tryCompleteVision(Long sessionId, String token, VisionAnalysisResult result) {
        String json;
        try {
            json = objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.error("Vision 분석 결과를 직렬화하지 못했습니다. analysisId={}", sessionId, e);
            return false;
        }

        VisionAttemptOutcome outcome;
        try {
            outcome = coordinator.complete(sessionId, token, json);
        } catch (RuntimeException e) {
            log.error("Vision 분석 결과 저장에 실패했습니다. analysisId={}", sessionId, e);
            return false; // DB 저장 자체가 실패 - ack 안 함, 미처리로 남김
        }

        if (outcome == VisionAttemptOutcome.OWNERSHIP_LOST) {
            log.warn("Vision 완료 저장 시 소유권을 상실했습니다(다른 시도가 이미 재선점함). analysisId={}", sessionId);
            return false;
        }
        return true; // COMMITTED 또는 ALREADY_FINALIZED
    }

    private boolean tryRecordVisionFailure(Long sessionId, String token, String failureMessage) {
        VisionAttemptOutcome outcome;
        try {
            outcome = failureRecorder.recordVisionFailure(sessionId, token, failureMessage);
        } catch (RuntimeException recordingError) {
            log.error("Vision 실패 상태 기록에 실패했습니다. analysisId={}", sessionId, recordingError);
            return false; // DB 저장 자체가 실패 - ack 안 함, 미처리로 남김
        }

        if (outcome == VisionAttemptOutcome.OWNERSHIP_LOST) {
            log.warn("Vision 실패 기록 시 소유권을 상실했습니다(다른 시도가 이미 재선점함). analysisId={}", sessionId);
            return false;
        }
        if (outcome == VisionAttemptOutcome.COMMITTED) {
            metrics.recordFinalFailure();
        }
        return ensureFailurePublished(sessionId); // COMMITTED 또는 ALREADY_FINALIZED
    }

    private void ackAfterEnsuringFailurePublished(MapRecord<String, String, String> record, Long sessionId) {
        if (ensureFailurePublished(sessionId)) {
            acknowledge(record);
        }
        // false면 ACK하지 않는다 - 다음 재전달에서 발행을 다시 시도한다.
    }

    // VISION_FAILED가 아니거나 이미 발행됐으면 아무 것도 하지 않고 true(ACK 가능)를 반환한다.
    // 발행이 필요하면 Redis(XADD) 뒤 DB 플래그 갱신까지 성공해야 true를 반환한다 - 둘 중
    // 하나라도 실패하면 false(ACK 금지)를 반환해, 다음 재전달에서 다시 시도하게 한다.
    // XADD 성공 + 플래그 갱신 실패 사이에서 재시도가 한 번 더 XADD할 수 있다 - at-least-once로
    // 명시한다(VisionFailureEvent.analysisId가 소비자 쪽 멱등 처리의 근거).
    private boolean ensureFailurePublished(Long sessionId) {
        Optional<VisionFailureEvent> pending = failureStreamRecorder.pendingFailureEvent(sessionId);
        if (pending.isEmpty()) {
            return true;
        }
        try {
            failureStreamProducer.publish(pending.get());
        } catch (RuntimeException e) {
            log.error("실패 이벤트 발행에 실패했습니다. analysisId={}", sessionId, e);
            return false;
        }
        try {
            failureStreamRecorder.markPublished(sessionId);
        } catch (RuntimeException e) {
            log.error("실패 이벤트 발행 플래그 갱신에 실패했습니다(재전달 시 중복 발행 가능 - at-least-once). analysisId={}",
                    sessionId, e);
            return false;
        }
        return true;
    }

    private void acknowledge(MapRecord<String, String, String> record) {
        redisTemplate.opsForStream().acknowledge(properties.getKey(), properties.getGroup(), record.getId());
    }

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > FAILURE_MESSAGE_MAX_LENGTH
                ? message.substring(0, FAILURE_MESSAGE_MAX_LENGTH)
                : message;
    }
}
