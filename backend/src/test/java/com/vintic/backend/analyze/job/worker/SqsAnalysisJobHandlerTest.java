package com.vintic.backend.analyze.job.worker;

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
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.ByteArrayInputStream;
import java.net.SocketTimeoutException;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// SqsAnalysisJobHandler의 처리 순서(역직렬화 -> claimForProcessing -> GetObject -> Processor ->
// ProductAnalysisJobFinalizationService를 통한 fenced COMPLETED/FAILED)와, claim/finalization
// 결과에 따라 DELETE/RETAIN이 정확히 갈리는지, ApproximateReceiveCount 기반 retryable 소진 판단,
// MDC가 처리 후 항상 정리되는지 검증한다. Processor/finalizationService는 여기서도 mock이므로
// 이 테스트가 실제 DB나 기존 AI/Vision 서비스를 호출하지 않는다는 것도 보장한다.
class SqsAnalysisJobHandlerTest {

    private static final String BUCKET = "test-bucket";
    private static final byte[] IMAGE_BYTES = "fake-image-bytes".getBytes();
    private static final long STALE_AFTER_SECONDS = 90L;
    private static final int MAX_RECEIVE_COUNT = 3;

    private final ProductAnalysisJobRepository jobRepository = mock(ProductAnalysisJobRepository.class);
    private final S3Client s3Client = mock(S3Client.class);
    private final AnalysisProcessor analysisProcessor = mock(AnalysisProcessor.class);
    private final ProductAnalysisJobFinalizationService finalizationService =
            mock(ProductAnalysisJobFinalizationService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WorkerRuntimeIdentity workerRuntimeIdentity =
            new WorkerRuntimeIdentity("worker-1", "server-1");
    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final AnalysisJobMetrics analysisJobMetrics = new AnalysisJobMetrics(meterRegistry);

    private final SqsAnalysisJobHandler handler = new SqsAnalysisJobHandler(
            jobRepository, s3Client, analysisProcessor, objectMapper, workerRuntimeIdentity,
            finalizationService, analysisJobMetrics, BUCKET, STALE_AFTER_SECONDS, MAX_RECEIVE_COUNT);

    @AfterEach
    void clearMdcLeak() {
        MDC.clear();
    }

    // 이 테스트 파일의 대다수 시나리오는 queueWaitMs 자체를 검증 대상으로 삼지 않으므로
    // sentTimestampEpochMillis를 null로 둬 queueWaitMs가 기록되지 않게 한다 - queueWaitMs
    // 계산/기록을 검증하는 시나리오는 이 헬퍼 대신 명시적으로 두 timestamp를 채운
    // ReceivedQueueMessage를 직접 만든다.
    private ReceivedQueueMessage messageFor(Long analysisId, Integer receiveCount) throws Exception {
        String body = objectMapper.writeValueAsString(AnalysisJobQueueMessage.forJob(analysisId));
        return new ReceivedQueueMessage("msg-1", body, receiveCount, null, 0L);
    }

    private ProductAnalysisJob jobWith(Long id, AnalysisJobStatus status) {
        ProductAnalysisJob job = ProductAnalysisJob.create(1L, "uploads/a.jpg", "key-" + id);
        ReflectionTestUtils.setField(job, "id", id);
        ReflectionTestUtils.setField(job, "status", status);
        return job;
    }

    private ResponseInputStream<GetObjectResponse> s3ObjectOf(byte[] bytes) {
        return new ResponseInputStream<>(
                GetObjectResponse.builder().build(),
                AbortableInputStream.create(new ByteArrayInputStream(bytes)));
    }

    // ---- 생성자 검증 ----

    @Test
    void staleAfterSeconds가_0이하이면_생성자가_거부한다() {
        assertThatThrownBy(() -> new SqsAnalysisJobHandler(
                jobRepository, s3Client, analysisProcessor, objectMapper, workerRuntimeIdentity,
                finalizationService, analysisJobMetrics, BUCKET, 0L, MAX_RECEIVE_COUNT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void maxReceiveCount가_0이하이면_생성자가_거부한다() {
        assertThatThrownBy(() -> new SqsAnalysisJobHandler(
                jobRepository, s3Client, analysisProcessor, objectMapper, workerRuntimeIdentity,
                finalizationService, analysisJobMetrics, BUCKET, STALE_AFTER_SECONDS, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- claim 실패 규칙 ----

    @Test
    void claim이_0건이고_job이_존재하지_않으면_RETAIN한다() throws Exception {
        when(jobRepository.claimForProcessing(eq(42L), any(), eq(STALE_AFTER_SECONDS * 1_000_000)))
                .thenReturn(0);
        when(jobRepository.findById(42L)).thenReturn(Optional.empty());

        SqsAnalysisJobHandler.Outcome outcome = handler.handle(messageFor(42L, 1));

        assertThat(outcome).isEqualTo(SqsAnalysisJobHandler.Outcome.RETAIN);
    }

    @Test
    void claim이_0건이고_실제_상태가_PROCESSING이면_Processor를_호출하지_않고_RETAIN한다() throws Exception {
        when(jobRepository.claimForProcessing(eq(42L), any(), any(Long.class))).thenReturn(0);
        when(jobRepository.findById(42L)).thenReturn(Optional.of(jobWith(42L, AnalysisJobStatus.PROCESSING)));

        SqsAnalysisJobHandler.Outcome outcome = handler.handle(messageFor(42L, 1));

        assertThat(outcome).isEqualTo(SqsAnalysisJobHandler.Outcome.RETAIN);
        verify(analysisProcessor, never()).process(any());
        verify(s3Client, never()).getObject(any(GetObjectRequest.class));
    }

    @Test
    void claim이_0건이고_실제_상태가_QUEUED이면_조회_경쟁으로_판단해_RETAIN한다() throws Exception {
        when(jobRepository.claimForProcessing(eq(42L), any(), any(Long.class))).thenReturn(0);
        when(jobRepository.findById(42L)).thenReturn(Optional.of(jobWith(42L, AnalysisJobStatus.QUEUED)));

        SqsAnalysisJobHandler.Outcome outcome = handler.handle(messageFor(42L, 1));

        assertThat(outcome).isEqualTo(SqsAnalysisJobHandler.Outcome.RETAIN);
    }

    @Test
    void claim이_0건이고_실제_상태가_COMPLETED이면_중복_메시지로_간주해_DELETE한다() throws Exception {
        when(jobRepository.claimForProcessing(eq(42L), any(), any(Long.class))).thenReturn(0);
        when(jobRepository.findById(42L)).thenReturn(Optional.of(jobWith(42L, AnalysisJobStatus.COMPLETED)));

        SqsAnalysisJobHandler.Outcome outcome = handler.handle(messageFor(42L, 1));

        assertThat(outcome).isEqualTo(SqsAnalysisJobHandler.Outcome.DELETE);
        verify(analysisProcessor, never()).process(any());
    }

    @Test
    void claim이_0건이고_실제_상태가_FAILED이면_중복_메시지로_간주해_DELETE한다() throws Exception {
        when(jobRepository.claimForProcessing(eq(42L), any(), any(Long.class))).thenReturn(0);
        when(jobRepository.findById(42L)).thenReturn(Optional.of(jobWith(42L, AnalysisJobStatus.FAILED)));

        SqsAnalysisJobHandler.Outcome outcome = handler.handle(messageFor(42L, 1));

        assertThat(outcome).isEqualTo(SqsAnalysisJobHandler.Outcome.DELETE);
    }

    // ---- claim 성공 이후 정상 흐름 ----

    @Test
    void 정상_흐름은_claim_S3_Processor_순서로_진행되고_COMPLETED면_DELETE한다() throws Exception {
        ProductAnalysisJob job = jobWith(42L, AnalysisJobStatus.QUEUED);
        when(jobRepository.claimForProcessing(eq(42L), eq("worker-1"), any(Long.class))).thenReturn(1);
        when(jobRepository.findById(42L)).thenReturn(Optional.of(job));
        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(s3ObjectOf(IMAGE_BYTES));
        when(analysisProcessor.process(any(AnalysisInput.class)))
                .thenReturn(new AnalysisPayload("ok"));
        when(finalizationService.complete(42L, "worker-1", "ok")).thenReturn(FinalizeOutcome.COMPLETED);

        SqsAnalysisJobHandler.Outcome outcome = handler.handle(messageFor(42L, 1));

        assertThat(outcome).isEqualTo(SqsAnalysisJobHandler.Outcome.DELETE);
        verify(s3Client).getObject(eq(GetObjectRequest.builder().bucket(BUCKET).key("uploads/a.jpg").build()));
        ArgumentCaptor<AnalysisInput> inputCaptor = ArgumentCaptor.forClass(AnalysisInput.class);
        verify(analysisProcessor).process(inputCaptor.capture());
        assertThat(inputCaptor.getValue().analysisId()).isEqualTo(42L);
        assertThat(inputCaptor.getValue().imageContent()).isEqualTo(IMAGE_BYTES);
        verify(finalizationService).complete(42L, "worker-1", "ok");
    }

    @Test
    void 완료_시점에_LEASE_LOST이면_RETAIN하고_결과를_덮어쓰지_않는다() throws Exception {
        ProductAnalysisJob job = jobWith(42L, AnalysisJobStatus.QUEUED);
        when(jobRepository.claimForProcessing(eq(42L), any(), any(Long.class))).thenReturn(1);
        when(jobRepository.findById(42L)).thenReturn(Optional.of(job));
        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(s3ObjectOf(IMAGE_BYTES));
        when(analysisProcessor.process(any(AnalysisInput.class))).thenReturn(new AnalysisPayload("ok"));
        when(finalizationService.complete(any(), any(), any())).thenReturn(FinalizeOutcome.LEASE_LOST);

        SqsAnalysisJobHandler.Outcome outcome = handler.handle(messageFor(42L, 1));

        assertThat(outcome).isEqualTo(SqsAnalysisJobHandler.Outcome.RETAIN);
    }

    @Test
    void finalizationService_complete가_예외를_던지면_RETAIN한다() throws Exception {
        // result INSERT/commit 실패(예: DataIntegrityViolationException)가 그대로 전파되는 경우를
        // 흉내낸다 - 실제 롤백 여부는 ProductAnalysisJobFinalizationServiceMySqlIT가 검증하고,
        // 여기서는 Handler가 그 예외를 삼켜 메시지를 지우지 않는지만 확인한다.
        ProductAnalysisJob job = jobWith(42L, AnalysisJobStatus.QUEUED);
        when(jobRepository.claimForProcessing(eq(42L), any(), any(Long.class))).thenReturn(1);
        when(jobRepository.findById(42L)).thenReturn(Optional.of(job));
        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(s3ObjectOf(IMAGE_BYTES));
        when(analysisProcessor.process(any(AnalysisInput.class))).thenReturn(new AnalysisPayload("ok"));
        when(finalizationService.complete(any(), any(), any())).thenThrow(new RuntimeException("commit failed"));

        SqsAnalysisJobHandler.Outcome outcome = handler.handle(messageFor(42L, 1));

        assertThat(outcome).isEqualTo(SqsAnalysisJobHandler.Outcome.RETAIN);
    }

    // ---- 영구 오류 ----

    @Test
    void S3_NoSuchKey는_영구_오류로_fail_성공하면_DELETE한다() throws Exception {
        ProductAnalysisJob job = jobWith(42L, AnalysisJobStatus.QUEUED);
        when(jobRepository.claimForProcessing(eq(42L), any(), any(Long.class))).thenReturn(1);
        when(jobRepository.findById(42L)).thenReturn(Optional.of(job));
        when(s3Client.getObject(any(GetObjectRequest.class))).thenThrow(NoSuchKeyException.builder().build());
        when(finalizationService.fail(42L, "worker-1")).thenReturn(FailOutcome.FAILED);

        SqsAnalysisJobHandler.Outcome outcome = handler.handle(messageFor(42L, 1));

        assertThat(outcome).isEqualTo(SqsAnalysisJobHandler.Outcome.DELETE);
        verify(finalizationService).fail(42L, "worker-1");
        verify(analysisProcessor, never()).process(any());
    }

    @Test
    void S3_NoSuchKey인데_fail이_LEASE_LOST이면_메시지를_지우지_않는다() throws Exception {
        ProductAnalysisJob job = jobWith(42L, AnalysisJobStatus.QUEUED);
        when(jobRepository.claimForProcessing(eq(42L), any(), any(Long.class))).thenReturn(1);
        when(jobRepository.findById(42L)).thenReturn(Optional.of(job));
        when(s3Client.getObject(any(GetObjectRequest.class))).thenThrow(NoSuchKeyException.builder().build());
        when(finalizationService.fail(any(), any())).thenReturn(FailOutcome.LEASE_LOST);

        SqsAnalysisJobHandler.Outcome outcome = handler.handle(messageFor(42L, 1));

        assertThat(outcome).isEqualTo(SqsAnalysisJobHandler.Outcome.RETAIN);
    }

    @Test
    void S3_5xx_네트워크_오류는_일시적_오류로_RETAIN하고_fail을_호출하지_않는다() throws Exception {
        ProductAnalysisJob job = jobWith(42L, AnalysisJobStatus.QUEUED);
        when(jobRepository.claimForProcessing(eq(42L), any(), any(Long.class))).thenReturn(1);
        when(jobRepository.findById(42L)).thenReturn(Optional.of(job));
        when(s3Client.getObject(any(GetObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(500).message("internal error").build());

        SqsAnalysisJobHandler.Outcome outcome = handler.handle(messageFor(42L, 1));

        assertThat(outcome).isEqualTo(SqsAnalysisJobHandler.Outcome.RETAIN);
        verify(finalizationService, never()).fail(any(), any());
        verify(analysisProcessor, never()).process(any());
    }

    @Test
    void S3_클라이언트_타임아웃도_일시적_오류로_RETAIN한다() throws Exception {
        ProductAnalysisJob job = jobWith(42L, AnalysisJobStatus.QUEUED);
        when(jobRepository.claimForProcessing(eq(42L), any(), any(Long.class))).thenReturn(1);
        when(jobRepository.findById(42L)).thenReturn(Optional.of(job));
        when(s3Client.getObject(any(GetObjectRequest.class)))
                .thenThrow(SdkClientException.create("connect timeout"));

        SqsAnalysisJobHandler.Outcome outcome = handler.handle(messageFor(42L, 1));

        assertThat(outcome).isEqualTo(SqsAnalysisJobHandler.Outcome.RETAIN);
    }

    @Test
    void Processor_영구_오류는_fail_성공하면_DELETE한다() throws Exception {
        ProductAnalysisJob job = jobWith(42L, AnalysisJobStatus.QUEUED);
        when(jobRepository.claimForProcessing(eq(42L), any(), any(Long.class))).thenReturn(1);
        when(jobRepository.findById(42L)).thenReturn(Optional.of(job));
        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(s3ObjectOf(IMAGE_BYTES));
        when(analysisProcessor.process(any(AnalysisInput.class)))
                .thenThrow(new AnalysisPermanentFailureException("bad input"));
        when(finalizationService.fail(42L, "worker-1")).thenReturn(FailOutcome.FAILED);

        SqsAnalysisJobHandler.Outcome outcome = handler.handle(messageFor(42L, 1));

        assertThat(outcome).isEqualTo(SqsAnalysisJobHandler.Outcome.DELETE);
        verify(finalizationService).fail(42L, "worker-1");
        verify(finalizationService, never()).complete(any(), any(), any());
    }

    @Test
    void Processor_일시적_오류는_receiveCount가_maxReceiveCount_미만이면_RETAIN하고_fail을_호출하지_않는다() throws Exception {
        ProductAnalysisJob job = jobWith(42L, AnalysisJobStatus.QUEUED);
        when(jobRepository.claimForProcessing(eq(42L), any(), any(Long.class))).thenReturn(1);
        when(jobRepository.findById(42L)).thenReturn(Optional.of(job));
        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(s3ObjectOf(IMAGE_BYTES));
        when(analysisProcessor.process(any(AnalysisInput.class)))
                .thenThrow(new AnalysisTransientFailureException("ai timeout"));

        SqsAnalysisJobHandler.Outcome outcome = handler.handle(messageFor(42L, MAX_RECEIVE_COUNT - 1));

        assertThat(outcome).isEqualTo(SqsAnalysisJobHandler.Outcome.RETAIN);
        verify(finalizationService, never()).fail(any(), any());
        verify(finalizationService, never()).complete(any(), any(), any());
    }

    @Test
    void Processor_일시적_오류는_receiveCount가_maxReceiveCount와_같으면_fenced_FAILED_Commit_후에도_RETAIN한다() throws Exception {
        ProductAnalysisJob job = jobWith(42L, AnalysisJobStatus.QUEUED);
        when(jobRepository.claimForProcessing(eq(42L), any(), any(Long.class))).thenReturn(1);
        when(jobRepository.findById(42L)).thenReturn(Optional.of(job));
        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(s3ObjectOf(IMAGE_BYTES));
        when(analysisProcessor.process(any(AnalysisInput.class)))
                .thenThrow(new AnalysisTransientFailureException("ai timeout"));
        when(finalizationService.fail(42L, "worker-1")).thenReturn(FailOutcome.FAILED);

        SqsAnalysisJobHandler.Outcome outcome = handler.handle(messageFor(42L, MAX_RECEIVE_COUNT));

        assertThat(outcome).isEqualTo(SqsAnalysisJobHandler.Outcome.RETAIN);
        verify(finalizationService).fail(42L, "worker-1");
    }

    @Test
    void Processor_일시적_오류는_receiveCount가_maxReceiveCount를_초과해도_방어적으로_fail을_호출하고_RETAIN한다() throws Exception {
        ProductAnalysisJob job = jobWith(42L, AnalysisJobStatus.QUEUED);
        when(jobRepository.claimForProcessing(eq(42L), any(), any(Long.class))).thenReturn(1);
        when(jobRepository.findById(42L)).thenReturn(Optional.of(job));
        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(s3ObjectOf(IMAGE_BYTES));
        when(analysisProcessor.process(any(AnalysisInput.class)))
                .thenThrow(new AnalysisTransientFailureException("ai timeout"));
        when(finalizationService.fail(42L, "worker-1")).thenReturn(FailOutcome.FAILED);

        SqsAnalysisJobHandler.Outcome outcome = handler.handle(messageFor(42L, MAX_RECEIVE_COUNT + 5));

        assertThat(outcome).isEqualTo(SqsAnalysisJobHandler.Outcome.RETAIN);
        verify(finalizationService).fail(42L, "worker-1");
    }

    @Test
    void Processor_일시적_오류인데_receiveCount가_null이면_보수적으로_RETAIN하고_fail을_호출하지_않는다() throws Exception {
        ProductAnalysisJob job = jobWith(42L, AnalysisJobStatus.QUEUED);
        when(jobRepository.claimForProcessing(eq(42L), any(), any(Long.class))).thenReturn(1);
        when(jobRepository.findById(42L)).thenReturn(Optional.of(job));
        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(s3ObjectOf(IMAGE_BYTES));
        when(analysisProcessor.process(any(AnalysisInput.class)))
                .thenThrow(new AnalysisTransientFailureException("ai timeout"));

        SqsAnalysisJobHandler.Outcome outcome = handler.handle(messageFor(42L, null));

        assertThat(outcome).isEqualTo(SqsAnalysisJobHandler.Outcome.RETAIN);
        verify(finalizationService, never()).fail(any(), any());
    }

    @Test
    void 분류되지_않은_예상밖_오류도_RETAIN하고_메시지를_지우지_않는다() throws Exception {
        ProductAnalysisJob job = jobWith(42L, AnalysisJobStatus.QUEUED);
        when(jobRepository.claimForProcessing(eq(42L), any(), any(Long.class))).thenReturn(1);
        when(jobRepository.findById(42L)).thenReturn(Optional.of(job));
        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(s3ObjectOf(IMAGE_BYTES));
        when(analysisProcessor.process(any(AnalysisInput.class)))
                .thenThrow(new RuntimeException("unexpected"));

        SqsAnalysisJobHandler.Outcome outcome = handler.handle(messageFor(42L, 1));

        assertThat(outcome).isEqualTo(SqsAnalysisJobHandler.Outcome.RETAIN);
        verify(finalizationService, never()).complete(any(), any(), any());
        verify(finalizationService, never()).fail(any(), any());
    }

    // ---- Day10 관측성: processor.calls/failures/latency, worker.events, queueWaitMs ----
    // 위 시나리오들과 같은 claim/S3/Processor/finalization mock 조합을 재사용해, DELETE/RETAIN
    // 판정은 그대로 두고 이번에 추가된 metric 기록만 검증한다.

    @Test
    void 성공하면_processor_calls_latency만_기록되고_failures는_증가하지_않는다() throws Exception {
        ProductAnalysisJob job = jobWith(42L, AnalysisJobStatus.QUEUED);
        when(jobRepository.claimForProcessing(eq(42L), any(), any(Long.class))).thenReturn(1);
        when(jobRepository.findById(42L)).thenReturn(Optional.of(job));
        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(s3ObjectOf(IMAGE_BYTES));
        when(analysisProcessor.process(any(AnalysisInput.class))).thenReturn(new AnalysisPayload("ok"));
        when(finalizationService.complete(any(), any(), any())).thenReturn(FinalizeOutcome.COMPLETED);

        handler.handle(messageFor(42L, 1));

        assertThat(meterRegistry.counter("autique.analysis.processor.calls", "outcome", "success").count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.find("autique.analysis.processor.failures").counter()).isNull();
        assertThat(meterRegistry.timer("autique.analysis.processor.latency", "outcome", "success").count())
                .isEqualTo(1L);
    }

    @Test
    void 완료_시점_LEASE_LOST는_worker_events에_lease_lost로_기록된다() throws Exception {
        ProductAnalysisJob job = jobWith(42L, AnalysisJobStatus.QUEUED);
        when(jobRepository.claimForProcessing(eq(42L), any(), any(Long.class))).thenReturn(1);
        when(jobRepository.findById(42L)).thenReturn(Optional.of(job));
        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(s3ObjectOf(IMAGE_BYTES));
        when(analysisProcessor.process(any(AnalysisInput.class))).thenReturn(new AnalysisPayload("ok"));
        when(finalizationService.complete(any(), any(), any())).thenReturn(FinalizeOutcome.LEASE_LOST);

        handler.handle(messageFor(42L, 1));

        assertThat(meterRegistry.counter("autique.analysis.worker.events", "event", "lease_lost").count())
                .isEqualTo(1.0);
    }

    @Test
    void 영구_오류는_processor_calls_permanent_failure와_failures를_기록한다() throws Exception {
        ProductAnalysisJob job = jobWith(42L, AnalysisJobStatus.QUEUED);
        when(jobRepository.claimForProcessing(eq(42L), any(), any(Long.class))).thenReturn(1);
        when(jobRepository.findById(42L)).thenReturn(Optional.of(job));
        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(s3ObjectOf(IMAGE_BYTES));
        when(analysisProcessor.process(any(AnalysisInput.class)))
                .thenThrow(new AnalysisPermanentFailureException("bad input"));
        when(finalizationService.fail(42L, "worker-1")).thenReturn(FailOutcome.FAILED);

        handler.handle(messageFor(42L, 1));

        assertThat(meterRegistry.counter("autique.analysis.processor.calls", "outcome", "permanent_failure").count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.counter("autique.analysis.processor.failures").count()).isEqualTo(1.0);
        assertThat(meterRegistry.timer("autique.analysis.processor.latency", "outcome", "permanent_failure").count())
                .isEqualTo(1L);
    }

    @Test
    void 일반_일시적_오류는_transient_failure로_분류되고_retry_event를_기록한다() throws Exception {
        ProductAnalysisJob job = jobWith(42L, AnalysisJobStatus.QUEUED);
        when(jobRepository.claimForProcessing(eq(42L), any(), any(Long.class))).thenReturn(1);
        when(jobRepository.findById(42L)).thenReturn(Optional.of(job));
        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(s3ObjectOf(IMAGE_BYTES));
        when(analysisProcessor.process(any(AnalysisInput.class)))
                .thenThrow(new AnalysisTransientFailureException("ai timeout"));

        handler.handle(messageFor(42L, MAX_RECEIVE_COUNT - 1));

        assertThat(meterRegistry.counter("autique.analysis.processor.calls", "outcome", "transient_failure").count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.counter("autique.analysis.processor.failures").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("autique.analysis.worker.events", "event", "retry").count())
                .isEqualTo(1.0);
    }

    @Test
    void cause_chain에_SocketTimeoutException이_있으면_timeout으로_분류한다() throws Exception {
        ProductAnalysisJob job = jobWith(42L, AnalysisJobStatus.QUEUED);
        when(jobRepository.claimForProcessing(eq(42L), any(), any(Long.class))).thenReturn(1);
        when(jobRepository.findById(42L)).thenReturn(Optional.of(job));
        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(s3ObjectOf(IMAGE_BYTES));
        when(analysisProcessor.process(any(AnalysisInput.class))).thenThrow(new AnalysisTransientFailureException(
                "ai timeout", new SocketTimeoutException("read timed out")));

        handler.handle(messageFor(42L, MAX_RECEIVE_COUNT - 1));

        assertThat(meterRegistry.counter("autique.analysis.processor.calls", "outcome", "timeout").count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.find("autique.analysis.processor.calls").tag("outcome", "transient_failure").counter())
                .isNull();
    }

    @Test
    void 최종_재시도_소진_fail이_LEASE_LOST이면_lease_lost_event를_기록한다() throws Exception {
        ProductAnalysisJob job = jobWith(42L, AnalysisJobStatus.QUEUED);
        when(jobRepository.claimForProcessing(eq(42L), any(), any(Long.class))).thenReturn(1);
        when(jobRepository.findById(42L)).thenReturn(Optional.of(job));
        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(s3ObjectOf(IMAGE_BYTES));
        when(analysisProcessor.process(any(AnalysisInput.class)))
                .thenThrow(new AnalysisTransientFailureException("ai timeout"));
        when(finalizationService.fail(42L, "worker-1")).thenReturn(FailOutcome.LEASE_LOST);

        handler.handle(messageFor(42L, MAX_RECEIVE_COUNT));

        assertThat(meterRegistry.counter("autique.analysis.worker.events", "event", "lease_lost").count())
                .isEqualTo(1.0);
    }

    @Test
    void receiveCount가_null이면_worker_event를_기록하지_않는다() throws Exception {
        ProductAnalysisJob job = jobWith(42L, AnalysisJobStatus.QUEUED);
        when(jobRepository.claimForProcessing(eq(42L), any(), any(Long.class))).thenReturn(1);
        when(jobRepository.findById(42L)).thenReturn(Optional.of(job));
        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(s3ObjectOf(IMAGE_BYTES));
        when(analysisProcessor.process(any(AnalysisInput.class)))
                .thenThrow(new AnalysisTransientFailureException("ai timeout"));

        handler.handle(messageFor(42L, null));

        assertThat(meterRegistry.find("autique.analysis.worker.events").counter()).isNull();
    }

    @Test
    void 분류되지_않은_예외는_unexpected_failure로_시간을_기록하고_unexpected_event도_기록한다() throws Exception {
        ProductAnalysisJob job = jobWith(42L, AnalysisJobStatus.QUEUED);
        when(jobRepository.claimForProcessing(eq(42L), any(), any(Long.class))).thenReturn(1);
        when(jobRepository.findById(42L)).thenReturn(Optional.of(job));
        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(s3ObjectOf(IMAGE_BYTES));
        when(analysisProcessor.process(any(AnalysisInput.class)))
                .thenThrow(new RuntimeException("unexpected"));

        SqsAnalysisJobHandler.Outcome outcome = handler.handle(messageFor(42L, 1));

        assertThat(outcome).isEqualTo(SqsAnalysisJobHandler.Outcome.RETAIN);
        assertThat(meterRegistry.counter("autique.analysis.processor.calls", "outcome", "unexpected_failure").count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.counter("autique.analysis.processor.failures").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("autique.analysis.worker.events", "event", "unexpected").count())
                .isEqualTo(1.0);
    }

    @Test
    void queueWaitMs가_있으면_queue_wait_timer에_밀리초로_기록된다() throws Exception {
        ProductAnalysisJob job = jobWith(42L, AnalysisJobStatus.QUEUED);
        when(jobRepository.claimForProcessing(eq(42L), any(), any(Long.class))).thenReturn(1);
        when(jobRepository.findById(42L)).thenReturn(Optional.of(job));
        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(s3ObjectOf(IMAGE_BYTES));
        when(analysisProcessor.process(any(AnalysisInput.class))).thenReturn(new AnalysisPayload("ok"));
        when(finalizationService.complete(any(), any(), any())).thenReturn(FinalizeOutcome.COMPLETED);
        String body = objectMapper.writeValueAsString(AnalysisJobQueueMessage.forJob(42L));
        ReceivedQueueMessage message = new ReceivedQueueMessage("msg-1", body, 1, 1_000L, 1_250L);

        handler.handle(message);

        assertThat(meterRegistry.timer("autique.analysis.queue.wait").totalTime(TimeUnit.MILLISECONDS))
                .isEqualTo(250.0);
    }

    @Test
    void queueWaitMs가_없으면_queue_wait_timer를_기록하지_않는다() throws Exception {
        ProductAnalysisJob job = jobWith(42L, AnalysisJobStatus.QUEUED);
        when(jobRepository.claimForProcessing(eq(42L), any(), any(Long.class))).thenReturn(1);
        when(jobRepository.findById(42L)).thenReturn(Optional.of(job));
        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(s3ObjectOf(IMAGE_BYTES));
        when(analysisProcessor.process(any(AnalysisInput.class))).thenReturn(new AnalysisPayload("ok"));
        when(finalizationService.complete(any(), any(), any())).thenReturn(FinalizeOutcome.COMPLETED);

        handler.handle(messageFor(42L, 1)); // sentTimestampEpochMillis == null

        assertThat(meterRegistry.find("autique.analysis.queue.wait").timer()).isNull();
    }

    // ---- 메시지 계약/파싱 ----

    @Test
    void JSON_파싱에_실패하면_RETAIN하고_claim조차_하지_않는다() {
        SqsAnalysisJobHandler.Outcome outcome =
                handler.handle(new ReceivedQueueMessage("msg-1", "not-json", 1, null, 0L));

        assertThat(outcome).isEqualTo(SqsAnalysisJobHandler.Outcome.RETAIN);
        verify(jobRepository, never()).claimForProcessing(any(), any(), any(Long.class));
    }

    @Test
    void eventVersion이_다르면_RETAIN한다() {
        SqsAnalysisJobHandler.Outcome outcome = handler.handle(
                new ReceivedQueueMessage("msg-1", "{\"eventVersion\":2,\"analysisId\":42}", 1, null, 0L));

        assertThat(outcome).isEqualTo(SqsAnalysisJobHandler.Outcome.RETAIN);
        verify(jobRepository, never()).claimForProcessing(any(), any(), any(Long.class));
    }

    @Test
    void analysisId가_없으면_RETAIN한다() {
        SqsAnalysisJobHandler.Outcome outcome = handler.handle(
                new ReceivedQueueMessage("msg-1", "{\"eventVersion\":1,\"analysisId\":null}", 1, null, 0L));

        assertThat(outcome).isEqualTo(SqsAnalysisJobHandler.Outcome.RETAIN);
        verify(jobRepository, never()).claimForProcessing(any(), any(), any(Long.class));
    }

    // ---- MDC ----

    @Test
    void 성공_경로에서_MDC_4개_필드가_설정되고_처리_후_모두_제거된다() throws Exception {
        ProductAnalysisJob job = jobWith(42L, AnalysisJobStatus.QUEUED);
        when(jobRepository.claimForProcessing(eq(42L), any(), any(Long.class))).thenReturn(1);
        when(jobRepository.findById(42L)).thenReturn(Optional.of(job));
        when(s3Client.getObject(any(GetObjectRequest.class))).thenAnswer(invocation -> {
            assertThat(MDC.get("requestId")).isEqualTo("msg-1");
            assertThat(MDC.get("workerId")).isEqualTo("worker-1");
            assertThat(MDC.get("serverId")).isEqualTo("server-1");
            assertThat(MDC.get("analysisId")).isEqualTo("42");
            return s3ObjectOf(IMAGE_BYTES);
        });
        when(analysisProcessor.process(any(AnalysisInput.class))).thenReturn(new AnalysisPayload("ok"));
        when(finalizationService.complete(any(), any(), any())).thenReturn(FinalizeOutcome.COMPLETED);

        handler.handle(messageFor(42L, 1));

        assertThat(MDC.get("requestId")).isNull();
        assertThat(MDC.get("workerId")).isNull();
        assertThat(MDC.get("serverId")).isNull();
        assertThat(MDC.get("analysisId")).isNull();
    }

    @Test
    void 예외_경로에서도_MDC가_모두_제거된다() throws Exception {
        ProductAnalysisJob job = jobWith(42L, AnalysisJobStatus.QUEUED);
        when(jobRepository.claimForProcessing(eq(42L), any(), any(Long.class))).thenReturn(1);
        when(jobRepository.findById(42L)).thenReturn(Optional.of(job));
        when(s3Client.getObject(any(GetObjectRequest.class))).thenThrow(new RuntimeException("boom"));

        SqsAnalysisJobHandler.Outcome outcome = handler.handle(messageFor(42L, 1));

        assertThat(outcome).isEqualTo(SqsAnalysisJobHandler.Outcome.RETAIN);
        assertThat(MDC.get("requestId")).isNull();
        assertThat(MDC.get("workerId")).isNull();
        assertThat(MDC.get("serverId")).isNull();
        assertThat(MDC.get("analysisId")).isNull();
    }
}
