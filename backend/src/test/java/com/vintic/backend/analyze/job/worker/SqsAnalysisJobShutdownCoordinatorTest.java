package com.vintic.backend.analyze.job.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.analyze.job.AnalysisJobStatus;
import com.vintic.backend.analyze.job.ProductAnalysisJob;
import com.vintic.backend.analyze.job.ProductAnalysisJobFinalizationService;
import com.vintic.backend.analyze.job.ProductAnalysisJobRepository;
import com.vintic.backend.analyze.job.metrics.AnalysisJobMetrics;
import com.vintic.backend.analyze.job.processor.FakeAnalysisProcessor;
import com.vintic.backend.analyze.job.queue.AnalysisJobQueueMessage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Day3 3단계 shutdown coordinator 검증. Poller는 poll thread와 작업 thread를 분리하지 않고
// 단일 스레드로 수신+처리를 순차 실행하므로(SqsAnalysisJobPoller), "in-flight 작업 추적"은
// 별도 자료구조가 필요 없다 - 그 스레드가 아직 살아있는 것 자체가 in-flight 신호다. 이
// 전제가 실제로 요구사항(신규 polling 중단/완료 대기/SqsClient 보존/bounded wait/스레드 정리)을
// 만족하는지 latch로 제어해 확인한다.
class SqsAnalysisJobShutdownCoordinatorTest {

    private static final String QUEUE_URL = "http://localhost/queue/test";
    private static final String THREAD_NAME = "sqs-analysis-job-poller";

    // 각 테스트가 남긴 대기 중인 latch/스레드가 다음 테스트로 새지 않도록 정리한다.
    private final List<CountDownLatch> latchesToRelease = new java.util.ArrayList<>();
    private SqsAnalysisJobPoller pollerUnderTest;

    @AfterEach
    void cleanUp() {
        latchesToRelease.forEach(CountDownLatch::countDown);
        if (pollerUnderTest != null) {
            pollerUnderTest.stop();
            pollerUnderTest.awaitTermination(Duration.ofSeconds(5));
        }
        MDC.clear();
    }

    private Message sqsMessage(Long analysisId) throws Exception {
        String body = new ObjectMapper().writeValueAsString(AnalysisJobQueueMessage.forJob(analysisId));
        return Message.builder().messageId("msg-" + analysisId).body(body).receiptHandle("receipt-" + analysisId).build();
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

    @Test
    void stop_신호_이후_신규_polling_없이_이미_받은_메시지만_기존_규칙대로_마무리한다() throws Exception {
        SqsClient sqsClient = mock(SqsClient.class);
        Message message = sqsMessage(42L);
        CountDownLatch handlingStarted = new CountDownLatch(1);
        CountDownLatch releaseHandling = new CountDownLatch(1);
        latchesToRelease.add(releaseHandling);

        SqsAnalysisJobHandler handler = mock(SqsAnalysisJobHandler.class);
        when(handler.handle(any(ReceivedQueueMessage.class))).thenAnswer(invocation -> {
            handlingStarted.countDown();
            assertThat(releaseHandling.await(5, TimeUnit.SECONDS)).isTrue();
            return SqsAnalysisJobHandler.Outcome.DELETE;
        });
        // 단 한 번만 stub한다 - stop() 신호가 제때 반영됐다면 두 번째 receiveMessage 호출은
        // 일어나지 않아야 하므로, 그 호출이 실제로 몇 번 일어났는지를 검증 대상으로 삼는다.
        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(ReceiveMessageResponse.builder().messages(List.of(message)).build());

        SqsAnalysisJobPoller poller = new SqsAnalysisJobPoller(sqsClient, handler, QUEUE_URL, 20, 90);
        pollerUnderTest = poller;

        poller.start();
        assertThat(handlingStarted.await(5, TimeUnit.SECONDS)).isTrue();
        poller.stop(); // in-flight 처리 도중 종료 신호
        releaseHandling.countDown(); // 처리 완료 허용

        assertThat(poller.awaitTermination(Duration.ofSeconds(5))).isTrue();

        verify(sqsClient, org.mockito.Mockito.times(1)).receiveMessage(any(ReceiveMessageRequest.class));
        verify(sqsClient).deleteMessage(DeleteMessageRequest.builder()
                .queueUrl(QUEUE_URL)
                .receiptHandle("receipt-42")
                .build());
        assertThat(Thread.getAllStackTraces().keySet().stream().noneMatch(t -> t.getName().equals(THREAD_NAME)))
                .as("종료 후 poller 스레드가 남아있지 않아야 한다")
                .isTrue();
    }

    @Test
    void timeout_안에_처리가_끝나지_않으면_awaitTermination이_false를_반환하고_무한정_대기하지_않는다() throws Exception {
        SqsClient sqsClient = mock(SqsClient.class);
        Message message = sqsMessage(42L);
        CountDownLatch handlingStarted = new CountDownLatch(1);
        CountDownLatch releaseHandling = new CountDownLatch(1);
        latchesToRelease.add(releaseHandling);

        SqsAnalysisJobHandler handler = mock(SqsAnalysisJobHandler.class);
        when(handler.handle(any(ReceivedQueueMessage.class))).thenAnswer(invocation -> {
            handlingStarted.countDown();
            releaseHandling.await(); // 테스트가 끝나기 전 @AfterEach에서 반드시 해제한다
            return SqsAnalysisJobHandler.Outcome.RETAIN;
        });
        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(ReceiveMessageResponse.builder().messages(List.of(message)).build());

        SqsAnalysisJobPoller poller = new SqsAnalysisJobPoller(sqsClient, handler, QUEUE_URL, 20, 90);
        pollerUnderTest = poller;

        poller.start();
        assertThat(handlingStarted.await(5, TimeUnit.SECONDS)).isTrue();
        poller.stop();

        long startNanos = System.nanoTime();
        boolean drained = poller.awaitTermination(Duration.ofMillis(300));
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

        assertThat(drained).isFalse();
        assertThat(elapsedMs).isLessThan(2_000);
    }

    @Test
    void shutdown_도중_처리되는_메시지도_MDC_4개_필드가_정상_설정된_채로_처리된다() throws Exception {
        ProductAnalysisJobRepository jobRepository = mock(ProductAnalysisJobRepository.class);
        S3Client s3Client = mock(S3Client.class);
        SqsClient sqsClient = mock(SqsClient.class);

        ProductAnalysisJob job = jobWith(42L, AnalysisJobStatus.QUEUED);
        when(jobRepository.findById(42L)).thenReturn(Optional.of(job));
        when(jobRepository.claimForProcessing(eq(42L), any(), anyLong())).thenReturn(1);
        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(s3ObjectOf("bytes".getBytes()));
        ProductAnalysisJobFinalizationService finalizationService = mock(ProductAnalysisJobFinalizationService.class);
        when(finalizationService.complete(eq(42L), any(), any()))
                .thenReturn(ProductAnalysisJobFinalizationService.FinalizeOutcome.COMPLETED);

        CountDownLatch handlingStarted = new CountDownLatch(1);
        CountDownLatch releaseHandling = new CountDownLatch(1);
        latchesToRelease.add(releaseHandling);
        AtomicReference<Map<String, String>> observedMdc = new AtomicReference<>();

        // FakeAnalysisProcessor(1단계)의 Sleeper 주입 지점을 그대로 재사용해 "제어 가능한 Fake
        // Processor"를 구성한다 - 별도 테스트 전용 Processor 클래스를 새로 만들지 않는다.
        FakeAnalysisProcessor processor = new FakeAnalysisProcessor(millis -> {
            observedMdc.set(MDC.getCopyOfContextMap());
            handlingStarted.countDown();
            try {
                assertThat(releaseHandling.await(5, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, 0);
        processor.setNextOutcome(FakeAnalysisProcessor.Outcome.SUCCESS);

        SqsAnalysisJobHandler handler = new SqsAnalysisJobHandler(
                jobRepository, s3Client, processor, new ObjectMapper(),
                new WorkerRuntimeIdentity("worker-1", "server-1"), finalizationService,
                new AnalysisJobMetrics(new SimpleMeterRegistry()),
                "test-bucket", 90L, 3);

        Message message = sqsMessage(42L);
        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(ReceiveMessageResponse.builder().messages(List.of(message)).build());

        SqsAnalysisJobPoller poller = new SqsAnalysisJobPoller(sqsClient, handler, QUEUE_URL, 20, 90);
        pollerUnderTest = poller;

        poller.start();
        assertThat(handlingStarted.await(5, TimeUnit.SECONDS)).isTrue();
        poller.stop(); // in-flight Processor 호출이 아직 안 끝난 상태에서 종료 신호
        releaseHandling.countDown();

        assertThat(poller.awaitTermination(Duration.ofSeconds(5))).isTrue();

        Map<String, String> mdc = observedMdc.get();
        assertThat(mdc).isNotNull();
        assertThat(mdc.get("requestId")).isEqualTo("msg-42");
        assertThat(mdc.get("workerId")).isEqualTo("worker-1");
        assertThat(mdc.get("serverId")).isEqualTo("server-1");
        assertThat(mdc.get("analysisId")).isEqualTo("42");
        verify(sqsClient).deleteMessage(any(DeleteMessageRequest.class));
        verify(sqsClient, never()).close();
    }
}
