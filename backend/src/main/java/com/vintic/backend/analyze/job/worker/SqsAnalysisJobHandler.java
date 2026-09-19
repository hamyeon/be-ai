package com.vintic.backend.analyze.job.worker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.analyze.job.AnalysisJobStatus;
import com.vintic.backend.analyze.job.ProductAnalysisJob;
import com.vintic.backend.analyze.job.ProductAnalysisJobFinalizationService;
import com.vintic.backend.analyze.job.ProductAnalysisJobFinalizationService.FailOutcome;
import com.vintic.backend.analyze.job.ProductAnalysisJobFinalizationService.FinalizeOutcome;
import com.vintic.backend.analyze.job.ProductAnalysisJobRepository;
import com.vintic.backend.analyze.job.metrics.AnalysisJobMetrics;
import com.vintic.backend.analyze.job.processor.AnalysisInput;
import com.vintic.backend.analyze.job.processor.AnalysisPayload;
import com.vintic.backend.analyze.job.processor.AnalysisProcessor;
import com.vintic.backend.analyze.job.queue.AnalysisJobQueueMessage;
import com.vintic.backend.common.exception.AnalysisPermanentFailureException;
import com.vintic.backend.common.exception.AnalysisTransientFailureException;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

// 메시지 1건 처리기. SQS long polling(poller) / lifecycle과 분리된 순수 처리 로직 -
// receiveMessage/deleteMessage는 poller가, start/stop 연결은 lifecycle이 담당한다.
//
// 처리 순서: 메시지 역직렬화(+계약 검증) -> claimForProcessing(원자적 claim/stale 재선점)
// -> S3 GetObject -> AnalysisProcessor.process() -> ProductAnalysisJobFinalizationService를
// 통한 fenced COMPLETED/FAILED 전이. 상태 전이는 항상 Repository의 조건부 UPDATE(row count
// 반환)로만 하고, 실제로 반영됐음을 확인한 뒤에만 DELETE를 반환한다.
//
// Day4: claim은 workerId(WorkerRuntimeIdentity.workerId() 재사용) fencing을 쓰고, 완료/영구
// 실패는 ProductAnalysisJobFinalizationService(별도 트랜잭션 Service)를 거친다 - Day3의
// unfenced markProcessing/markCompleted/markFailed(현재 Repository에서 제거됨) 호출과
// "job 없음 -> DELETE" 규칙은 여기서 완전히 대체됐다(job 없음은 이제 데이터 불일치 경고 + RETAIN).
// Day10: analysisId/requestId/workerId/serverId/objectKey 같은 unbounded 값은 metric
// dimension으로 쓰지 않는다(AnalysisJobMetrics 참고) - MDC/로그에만 남긴다.
//
// experiment-worker 전용 Bean으로 제한한다(AnalysisJobMetrics와 동일한 이유) - API 프로세스는
// 이 Handler를 쓰지 않는다.
@Component
@Profile("experiment-worker")
@Slf4j
public class SqsAnalysisJobHandler {

    public enum Outcome {
        DELETE,
        RETAIN
    }

    private static final String MDC_ANALYSIS_ID = "analysisId";
    private static final String MDC_REQUEST_ID = "requestId";
    private static final String MDC_WORKER_ID = "workerId";
    private static final String MDC_SERVER_ID = "serverId";

    private final ProductAnalysisJobRepository jobRepository;
    private final S3Client s3Client;
    private final AnalysisProcessor analysisProcessor;
    private final ObjectMapper objectMapper;
    private final ProductAnalysisJobFinalizationService finalizationService;
    private final AnalysisJobMetrics analysisJobMetrics;
    private final String workerId;
    private final String serverId;
    private final String bucket;
    private final long staleAfterMicros;
    private final int maxReceiveCount;

    public SqsAnalysisJobHandler(
            ProductAnalysisJobRepository jobRepository,
            S3Client s3Client,
            AnalysisProcessor analysisProcessor,
            ObjectMapper objectMapper,
            WorkerRuntimeIdentity workerRuntimeIdentity,
            ProductAnalysisJobFinalizationService finalizationService,
            AnalysisJobMetrics analysisJobMetrics,
            @Value("${cloud.aws.s3.bucket}") String bucket,
            @Value("${analysis.worker.stale-after-seconds:80}") long staleAfterSeconds,
            @Value("${analysis.worker.max-receive-count:3}") int maxReceiveCount
    ) {
        if (staleAfterSeconds <= 0) {
            throw new IllegalArgumentException(
                    "analysis.worker.stale-after-seconds는 양수여야 합니다: " + staleAfterSeconds);
        }
        if (maxReceiveCount <= 0) {
            throw new IllegalArgumentException(
                    "analysis.worker.max-receive-count는 양수여야 합니다: " + maxReceiveCount);
        }
        this.jobRepository = jobRepository;
        this.s3Client = s3Client;
        this.analysisProcessor = analysisProcessor;
        this.objectMapper = objectMapper;
        this.finalizationService = finalizationService;
        this.analysisJobMetrics = analysisJobMetrics;
        this.workerId = workerRuntimeIdentity.workerId();
        this.serverId = workerRuntimeIdentity.serverId();
        this.bucket = bucket;
        this.staleAfterMicros = TimeUnit.SECONDS.toMicros(staleAfterSeconds);
        this.maxReceiveCount = maxReceiveCount;
    }

    public Outcome handle(ReceivedQueueMessage message) {
        MDC.put(MDC_REQUEST_ID, message.messageId());
        MDC.put(MDC_WORKER_ID, workerId);
        MDC.put(MDC_SERVER_ID, serverId);
        try {
            return process(message);
        } catch (Exception e) {
            // 분류되지 않은 예상 밖 오류(finalizationService의 result INSERT/commit 실패 포함) -
            // 메시지 유실을 피하는 것이 우선이므로 RETAIN한다.
            log.error("분석 작업 메시지 처리 중 예상하지 못한 오류가 발생했습니다.", e);
            analysisJobMetrics.recordWorkerEvent(AnalysisJobMetrics.WorkerEvent.UNEXPECTED);
            return Outcome.RETAIN;
        } finally {
            MDC.remove(MDC_ANALYSIS_ID);
            MDC.remove(MDC_REQUEST_ID);
            MDC.remove(MDC_WORKER_ID);
            MDC.remove(MDC_SERVER_ID);
        }
    }

    private Outcome process(ReceivedQueueMessage message) {
        AnalysisJobQueueMessage payload;
        try {
            payload = objectMapper.readValue(message.body(), AnalysisJobQueueMessage.class);
        } catch (JsonProcessingException e) {
            log.warn("분석 작업 메시지 역직렬화에 실패했습니다: {}", e.getMessage());
            return Outcome.RETAIN;
        }

        if (payload.eventVersion() != AnalysisJobQueueMessage.CURRENT_EVENT_VERSION || payload.analysisId() == null) {
            log.warn("지원하지 않는 메시지 계약입니다 - eventVersion={}, analysisId={}",
                    payload.eventVersion(), payload.analysisId());
            return Outcome.RETAIN;
        }

        Long analysisId = payload.analysisId();
        MDC.put(MDC_ANALYSIS_ID, String.valueOf(analysisId));
        logQueueWait(message);

        int claimed = jobRepository.claimForProcessing(analysisId, workerId, staleAfterMicros);
        if (claimed == 0) {
            return handleClaimFailure(analysisId);
        }

        ProductAnalysisJob job = jobRepository.findById(analysisId)
                .orElseThrow(() -> new IllegalStateException(
                        "claimForProcessing 직후 job을 찾을 수 없습니다. analysisId=" + analysisId));

        byte[] imageContent;
        try (ResponseInputStream<GetObjectResponse> s3Object = s3Client.getObject(GetObjectRequest.builder()
                .bucket(bucket)
                .key(job.getObjectKey())
                .build())) {
            imageContent = s3Object.readAllBytes();
        } catch (NoSuchKeyException e) {
            log.warn("objectKey가 존재하지 않습니다 - 영구 오류로 처리합니다.");
            return finalizePermanentFailure(analysisId);
        } catch (IOException e) {
            log.warn("S3 객체 본문을 읽는 중 오류가 발생했습니다: {}", e.getMessage());
            return Outcome.RETAIN;
        } catch (SdkException e) {
            log.warn("S3 GetObject 호출 중 일시적 오류가 발생했습니다: {}", e.getMessage());
            return Outcome.RETAIN;
        }

        AnalysisPayload result;
        Timer.Sample processorSample = analysisJobMetrics.startProcessorTimer();
        try {
            result = analysisProcessor.process(new AnalysisInput(analysisId, imageContent));
        } catch (AnalysisPermanentFailureException e) {
            analysisJobMetrics.recordProcessorResult(processorSample, AnalysisJobMetrics.ProcessorOutcome.PERMANENT_FAILURE);
            log.warn("Processor가 영구 오류를 보고했습니다: {}", e.getMessage());
            return finalizePermanentFailure(analysisId);
        } catch (AnalysisTransientFailureException e) {
            AnalysisJobMetrics.ProcessorOutcome outcome = hasTimeoutCause(e.getCause())
                    ? AnalysisJobMetrics.ProcessorOutcome.TIMEOUT
                    : AnalysisJobMetrics.ProcessorOutcome.TRANSIENT_FAILURE;
            analysisJobMetrics.recordProcessorResult(processorSample, outcome);
            log.warn("Processor가 일시적 오류를 보고했습니다: {}", e.getMessage());
            return handleTransientFailure(analysisId, message.approximateReceiveCount());
        } catch (RuntimeException e) {
            // 분류되지 않은 예상 밖 오류 - unexpected_failure로 시간만 기록하고, DB 상태 전이는
            // 건드리지 않은 채 그대로 다시 던져 바깥 catch(handle())가 기존 규칙대로 RETAIN하게 한다.
            analysisJobMetrics.recordProcessorResult(processorSample, AnalysisJobMetrics.ProcessorOutcome.UNEXPECTED_FAILURE);
            throw e;
        }

        analysisJobMetrics.recordProcessorResult(processorSample, AnalysisJobMetrics.ProcessorOutcome.SUCCESS);
        log.info("분석 처리에 성공했습니다.");
        return finalizeSuccess(analysisId, result.rawResult());
    }

    // AnalysisTransientFailureException의 cause chain에 SocketTimeoutException/
    // HttpTimeoutException/TimeoutException이 있으면 timeout으로, 없으면 일반 transient_failure로
    // 분류한다 - 예외 메시지/클래스명 자체는 dimension으로 쓰지 않고 이 고정된 두 값으로만 나눈다.
    private static boolean hasTimeoutCause(Throwable cause) {
        Throwable current = cause;
        while (current != null) {
            if (current instanceof SocketTimeoutException
                    || current instanceof HttpTimeoutException
                    || current instanceof TimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    // queueWaitMs/receiveCount를 SLF4J 2.x fluent key-value로 구조화 로그에 남긴다(URL/objectKey/
    // 자격증명은 남기지 않는다) - analysisId/requestId/workerId/serverId는 기존 MDC로 이미 남는다.
    // SentTimestamp를 확인할 수 없어 queueWaitMs를 계산하지 못한 경우는 receiveCount만 포함해
    // warn으로 남기고, 메시지 처리 자체는 그대로 진행한다.
    private void logQueueWait(ReceivedQueueMessage message) {
        Long queueWaitMs = message.queueWaitMs();
        if (queueWaitMs == null) {
            log.atWarn()
                    .addKeyValue("receiveCount", message.approximateReceiveCount())
                    .log("SentTimestamp가 없거나 유효하지 않아 queueWaitMs를 기록하지 않았습니다.");
            return;
        }
        log.atInfo()
                .addKeyValue("queueWaitMs", queueWaitMs)
                .addKeyValue("receiveCount", message.approximateReceiveCount())
                .log("SQS 메시지를 수신했습니다.");
        analysisJobMetrics.recordQueueWait(queueWaitMs);
    }

    // claimForProcessing이 0건을 반영했을 때(신규/stale 대상이 아니었을 때) 실제 상태를 재조회해
    // 최종 규칙을 적용한다. Day3의 "job 없음이면 DELETE"는 더 이상 유효하지 않다 - job이 없는
    // 것은 정상적인 작업 흐름에서는 나타나지 않아야 할 데이터 불일치이므로, 함부로 메시지를
    // 지우지 않고 경고 후 유지한다.
    private Outcome handleClaimFailure(Long analysisId) {
        Optional<ProductAnalysisJob> jobOpt = jobRepository.findById(analysisId);
        if (jobOpt.isEmpty()) {
            log.warn("claim 실패 후 재조회에서도 job을 찾을 수 없습니다 - 데이터 불일치 가능성이 있어 메시지를 유지합니다.");
            return Outcome.RETAIN;
        }
        AnalysisJobStatus status = jobOpt.get().getStatus();
        if (isTerminal(status)) {
            log.info("이미 종료된(terminal) job에 대한 중복 배달로 판단해 메시지를 삭제합니다 - currentStatus={}", status);
            return Outcome.DELETE;
        }
        log.warn("claim에 실패했습니다 - 다른 Worker가 처리 중이거나 아직 stale이 아닙니다. currentStatus={}", status);
        return Outcome.RETAIN;
    }

    private Outcome finalizeSuccess(Long analysisId, String rawResult) {
        FinalizeOutcome outcome = finalizationService.complete(analysisId, workerId, rawResult);
        if (outcome == FinalizeOutcome.COMPLETED) {
            return Outcome.DELETE;
        }
        // LEASE_LOST: 완료 시점에 이미 다른 Worker가 재선점했거나 종료됐다 - 이 delivery가 만든
        // 결과가 없으므로(저장하지 않았다) 상태를 다시 조회해 덮어쓰지 않고 그대로 RETAIN한다.
        analysisJobMetrics.recordWorkerEvent(AnalysisJobMetrics.WorkerEvent.LEASE_LOST);
        return Outcome.RETAIN;
    }

    // 영구 오류(S3 NoSuchKey, AnalysisPermanentFailureException) 전용 경로. retryable 소진과
    // 달리, fenced FAILED Commit에 성공하면 이 delivery를 바로 삭제한다 - DLQ 이동을 기다릴
    // 이유가 없는 확정적 영구 오류이기 때문이다.
    private Outcome finalizePermanentFailure(Long analysisId) {
        FailOutcome outcome = finalizationService.fail(analysisId, workerId);
        if (outcome == FailOutcome.FAILED) {
            return Outcome.DELETE;
        }
        analysisJobMetrics.recordWorkerEvent(AnalysisJobMetrics.WorkerEvent.LEASE_LOST);
        return Outcome.RETAIN;
    }

    // retryable(일시적) 오류 경로. ApproximateReceiveCount가 maxReceiveCount 미만이면 상태를
    // PROCESSING으로 유지한 채(fail 호출 없이) RETAIN해 SQS 재노출 -> stale 재선점으로 재시도되게
    // 한다. maxReceiveCount 이상(정상적으로는 ==이지만 방어적으로 >=)이면 fenced FAILED로 DB를
    // 확정하되, 메시지는 지우지 않는다 - SQS Redrive Policy가 이후 재노출 시 DLQ로 옮기도록
    // 그대로 둔다(이 delivery에서 Worker가 직접 DLQ로 보내지 않는다).
    private Outcome handleTransientFailure(Long analysisId, Integer approximateReceiveCount) {
        if (approximateReceiveCount == null) {
            log.warn("ApproximateReceiveCount를 확인할 수 없습니다 - 재시도 소진 여부를 판단할 수 없어 " +
                    "보수적으로 메시지를 유지합니다.");
            return Outcome.RETAIN;
        }
        if (approximateReceiveCount < maxReceiveCount) {
            log.info("일시적 오류입니다 - 재시도를 위해 메시지를 유지합니다. receiveCount={}/{}",
                    approximateReceiveCount, maxReceiveCount);
            analysisJobMetrics.recordWorkerEvent(AnalysisJobMetrics.WorkerEvent.RETRY);
            return Outcome.RETAIN;
        }

        log.warn("최종 허용 receive 횟수에 도달한 일시적 오류입니다 - fenced FAILED로 확정하되 " +
                "DLQ 이동을 위해 메시지는 삭제하지 않습니다. receiveCount={}/{}", approximateReceiveCount, maxReceiveCount);
        FailOutcome outcome = finalizationService.fail(analysisId, workerId);
        if (outcome == FailOutcome.LEASE_LOST) {
            log.warn("최종 재시도 FAILED 처리 시점에 이미 lease를 상실했습니다.");
            analysisJobMetrics.recordWorkerEvent(AnalysisJobMetrics.WorkerEvent.LEASE_LOST);
        }
        return Outcome.RETAIN;
    }

    private boolean isTerminal(AnalysisJobStatus status) {
        return status == AnalysisJobStatus.COMPLETED || status == AnalysisJobStatus.FAILED;
    }
}
