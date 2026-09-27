package com.vintic.backend.analyze.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.ai.vision.dto.ConditionGrade;
import com.vintic.backend.ai.vision.dto.VisionAnalysisResult;
import com.vintic.backend.ai.vision.service.VisionAnalysisService;
import com.vintic.backend.analyze.domain.VisionAttemptOutcome;
import com.vintic.backend.analyze.domain.VisionFailureAttemptResult;
import com.vintic.backend.analyze.service.AnalysisFailureRecorder;
import com.vintic.backend.analyze.service.VisionAttemptCoordinator;
import com.vintic.backend.analyze.service.VisionFailureStreamRecorder;
import com.vintic.backend.common.exception.AiApiException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.HttpStatusCodeException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalysisTaskConsumerTest {

    @Mock
    private VisionAnalysisService visionAnalysisService;

    @Mock
    private VisionAttemptCoordinator coordinator;

    @Mock
    private AnalysisFailureRecorder failureRecorder;

    @Mock
    private VisionFailureStreamRecorder failureStreamRecorder;

    @Mock
    private VisionFailureStreamProducer failureStreamProducer;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private StreamOperations<String, Object, Object> streamOperations;

    // 실제 의존성이 없는 순수 판정 로직이라 mock 대신 실제 인스턴스를 쓴다.
    private final VisionFailureClassifier failureClassifier = new VisionFailureClassifier();
    private final AnalysisStreamMetrics metrics = new AnalysisStreamMetrics(new SimpleMeterRegistry());

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AnalysisStreamProperties properties = new AnalysisStreamProperties();
    private final AnalysisVisionProcessingProperties visionProperties = new AnalysisVisionProcessingProperties();
    private ExecutorService visionExecutor;

    @BeforeEach
    void setUp() {
        visionProperties.setOverallTimeoutMs(1000L); // 테스트는 짧은 상한으로 타임아웃 시나리오를 빠르게 검증
        visionProperties.setMaxVisionFailureAttempts(3);
        visionExecutor = Executors.newFixedThreadPool(2);
        // 기본값: 발행할 실패 이벤트가 없다고 가정 - 실패 Stream 자체를 검증하는 테스트에서 재정의한다.
        // lenient(): 이 경로를 타지 않는 테스트(정상 완료 등)에서는 "쓰이지 않은 스텁"으로 잡히지 않게 한다.
        lenient().when(failureStreamRecorder.pendingFailureEvent(anyLong())).thenReturn(Optional.empty());
    }

    @AfterEach
    void tearDown() {
        visionExecutor.shutdownNow();
    }

    private AnalysisTaskConsumer newConsumer() {
        return newConsumer(visionExecutor);
    }

    private AnalysisTaskConsumer newConsumer(ExecutorService executor) {
        return new AnalysisTaskConsumer(
                visionAnalysisService, coordinator, failureRecorder, failureStreamRecorder, failureStreamProducer,
                failureClassifier, metrics, objectMapper, redisTemplate, properties, visionProperties, executor
        );
    }

    private MapRecord<String, String, String> recordFor(Long analysisId, List<String> imageUrls) {
        try {
            String payload = objectMapper.writeValueAsString(new AnalysisTaskMessage(analysisId, imageUrls, null));
            return StreamRecords.<String, String, String>mapBacked(Map.of("payload", payload))
                    .withStreamKey(properties.getKey())
                    .withId(RecordId.of("1-0"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private VisionAnalysisResult sampleResult() {
        return new VisionAnalysisResult(
                "Nike", "Dunk Low", "Panda", 270, "설명", ConditionGrade.B,
                true, 0.9, false, List.of(), List.of(), List.of(), List.of()
        );
    }

    // 4xx(429 제외) - VisionFailureClassifier가 재시도 불가로 판정한다.
    private AiApiException nonRetryableHttpError() {
        HttpStatusCodeException statusError = HttpClientErrorException.create(
                HttpStatus.BAD_REQUEST, "Bad Request", HttpHeaders.EMPTY, new byte[0], null);
        return new AiApiException("OpenAI Vision API 오류 (status=400): 잘못된 요청", statusError);
    }

    // 5xx - VisionFailureClassifier가 재시도 가치가 있다고 판정한다.
    private AiApiException retryableHttpError() {
        HttpStatusCodeException statusError = HttpServerErrorException.create(
                HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", HttpHeaders.EMPTY, new byte[0], null);
        return new AiApiException("OpenAI Vision API 오류 (status=500): 서버 오류", statusError);
    }

    // ---------- onMessage: 정상 배달 ----------

    @Test
    void 정상_처리되면_Vision_결과를_저장하고_ACK한다() {
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        List<String> imageUrls = List.of("https://example.com/a.jpg");
        when(coordinator.claim(eq(1L), anyString())).thenReturn(VisionAttemptOutcome.COMMITTED);
        when(visionAnalysisService.analyze(any())).thenReturn(sampleResult());
        when(coordinator.complete(eq(1L), anyString(), anyString())).thenReturn(VisionAttemptOutcome.COMMITTED);

        newConsumer().onMessage(recordFor(1L, imageUrls));

        verify(streamOperations).acknowledge(eq(properties.getKey()), eq(properties.getGroup()), eq(RecordId.of("1-0")));
    }

    @Test
    void 재시도_불가능한_오류는_1회차에도_즉시_최종_실패로_기록하고_ACK한다() {
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        when(coordinator.claim(eq(1L), anyString())).thenReturn(VisionAttemptOutcome.COMMITTED);
        when(visionAnalysisService.analyze(any())).thenThrow(nonRetryableHttpError());
        when(failureRecorder.recordVisionFailure(eq(1L), anyString(), anyString()))
                .thenReturn(VisionAttemptOutcome.COMMITTED);

        newConsumer().onMessage(recordFor(1L, List.of("https://example.com/a.jpg")));

        verify(failureRecorder).recordVisionFailure(eq(1L), anyString(), anyString());
        verify(streamOperations).acknowledge(eq(properties.getKey()), eq(properties.getGroup()), eq(RecordId.of("1-0")));
    }

    @Test
    void 재시도_가능한_오류는_실제_시도_횟수가_남아있으면_실패로_기록하지_않고_ACK도_하지_않는다() {
        // OpenAiVisionClient가 이미 단계별로 재시도를 다 소진한 뒤에도 5xx가 반복되는 상황.
        // 여기서 새로 재시도를 반복하지 않고, ACK하지 않은 채로 남겨 PEL 회수가 나중에 다시
        // 넘겨주게 한다 - 그것이 이 설계의 "재시도"다.
        when(coordinator.claim(eq(1L), anyString())).thenReturn(VisionAttemptOutcome.COMMITTED);
        when(visionAnalysisService.analyze(any())).thenThrow(retryableHttpError());
        when(coordinator.incrementFailureAttempt(eq(1L), anyString()))
                .thenReturn(new VisionFailureAttemptResult(VisionAttemptOutcome.COMMITTED, 1));

        newConsumer().onMessage(recordFor(1L, List.of("https://example.com/a.jpg")));

        verify(failureRecorder, never()).recordVisionFailure(any(), any(), any());
        verify(redisTemplate, never()).opsForStream();
    }

    @Test
    void 재시도_가능한_오류라도_실제_시도_횟수_상한을_넘으면_최종_실패로_기록하고_ACK한다() {
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        when(coordinator.reclaim(eq(1L), anyString())).thenReturn(VisionAttemptOutcome.COMMITTED);
        when(visionAnalysisService.analyze(any())).thenThrow(retryableHttpError());
        when(coordinator.incrementFailureAttempt(eq(1L), anyString()))
                .thenReturn(new VisionFailureAttemptResult(VisionAttemptOutcome.COMMITTED, 3)); // maxVisionFailureAttempts=3
        when(failureRecorder.recordVisionFailure(eq(1L), anyString(), anyString()))
                .thenReturn(VisionAttemptOutcome.COMMITTED);

        // deliveryCount는 이제 로그용일 뿐 상한 판정에 쓰이지 않는다 - 아무 값이나 넘겨도 된다.
        newConsumer().processReclaimed(recordFor(1L, List.of("https://example.com/a.jpg")), 1L);

        verify(failureRecorder).recordVisionFailure(eq(1L), anyString(), anyString());
        verify(streamOperations).acknowledge(eq(properties.getKey()), eq(properties.getGroup()), eq(RecordId.of("1-0")));
    }

    @Test
    void 처리_상한을_넘긴_타임아웃은_실제_시도_횟수가_남아있으면_ACK하지_않는다() {
        when(coordinator.claim(eq(1L), anyString())).thenReturn(VisionAttemptOutcome.COMMITTED);
        when(visionAnalysisService.analyze(any())).thenAnswer(invocation -> {
            TimeUnit.SECONDS.sleep(5); // visionProperties.overallTimeoutMs(1000ms)보다 길게 걸림
            return sampleResult();
        });
        when(coordinator.incrementFailureAttempt(eq(1L), anyString()))
                .thenReturn(new VisionFailureAttemptResult(VisionAttemptOutcome.COMMITTED, 1));

        newConsumer().onMessage(recordFor(1L, List.of("https://example.com/a.jpg")));

        verify(failureRecorder, never()).recordVisionFailure(any(), any(), any());
        verify(redisTemplate, never()).opsForStream();
    }

    @Test
    void 재시도_실제_시도_횟수_기록_시_소유권을_상실하면_ACK하지_않는다() {
        // A가 claim해 Vision을 호출했다가 재시도 가능한 오류로 실패했지만, 그사이 B가 이미
        // 재선점한 상황 - 실패 횟수 기록조차 반영되면 안 된다.
        when(coordinator.claim(eq(1L), anyString())).thenReturn(VisionAttemptOutcome.COMMITTED);
        when(visionAnalysisService.analyze(any())).thenThrow(retryableHttpError());
        when(coordinator.incrementFailureAttempt(eq(1L), anyString()))
                .thenReturn(new VisionFailureAttemptResult(VisionAttemptOutcome.OWNERSHIP_LOST, 0));

        newConsumer().onMessage(recordFor(1L, List.of("https://example.com/a.jpg")));

        verify(failureRecorder, never()).recordVisionFailure(any(), any(), any());
        verify(redisTemplate, never()).opsForStream();
    }

    @Test
    void 재시도_실제_시도_횟수_기록이_DB_오류로_실패하면_ACK하지_않는다() {
        when(coordinator.claim(eq(1L), anyString())).thenReturn(VisionAttemptOutcome.COMMITTED);
        when(visionAnalysisService.analyze(any())).thenThrow(retryableHttpError());
        when(coordinator.incrementFailureAttempt(eq(1L), anyString())).thenThrow(new RuntimeException("DB 연결 실패"));

        newConsumer().onMessage(recordFor(1L, List.of("https://example.com/a.jpg")));

        verify(failureRecorder, never()).recordVisionFailure(any(), any(), any());
        verify(redisTemplate, never()).opsForStream();
    }

    @Test
    void 재시도_실제_시도_횟수_기록_시점에_이미_종료됐으면_실패_Stream_확인_후_ACK한다() {
        // 배달 사이 다른 시도가 먼저 최종 확정(성공 또는 실패)해버린 상황.
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        when(coordinator.claim(eq(1L), anyString())).thenReturn(VisionAttemptOutcome.COMMITTED);
        when(visionAnalysisService.analyze(any())).thenThrow(retryableHttpError());
        when(coordinator.incrementFailureAttempt(eq(1L), anyString()))
                .thenReturn(new VisionFailureAttemptResult(VisionAttemptOutcome.ALREADY_FINALIZED, 0));

        newConsumer().onMessage(recordFor(1L, List.of("https://example.com/a.jpg")));

        verify(failureRecorder, never()).recordVisionFailure(any(), any(), any());
        verify(streamOperations).acknowledge(eq(properties.getKey()), eq(properties.getGroup()), eq(RecordId.of("1-0")));
    }

    @Test
    void executor_풀과_큐가_가득_차도_배달_횟수가_남아있으면_영구_실패로_기록하지_않는다() {
        // 좀비 시도(타임아웃났지만 readTimeout이 지날 때까지 스레드를 붙잡고 있는 시도)가 쌓여
        // pool+queue가 가득 찬 상황을 흉내낸다 - 로컬 과부하는 재시도 대상이라 ACK하지 않는다.
        CountDownLatch neverCompletes = new CountDownLatch(1);
        ExecutorService saturatedExecutor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1)
        );
        try {
            saturatedExecutor.submit(() -> {
                neverCompletes.await();
                return null;
            });
            saturatedExecutor.submit(() -> {
                neverCompletes.await();
                return null;
            });

            when(coordinator.claim(eq(1L), anyString())).thenReturn(VisionAttemptOutcome.COMMITTED);

            newConsumer(saturatedExecutor).onMessage(recordFor(1L, List.of("https://example.com/a.jpg")));

            verify(visionAnalysisService, never()).analyze(any());
            verify(failureRecorder, never()).recordVisionFailure(any(), any(), any());
            verify(redisTemplate, never()).opsForStream();
        } finally {
            neverCompletes.countDown();
            saturatedExecutor.shutdownNow();
        }
    }

    @Test
    void executor_포화는_배달_횟수_상한을_넘겨도_최종_실패로_기록하지_않는다() {
        // executor 포화는 Vision을 아예 시도조차 못한 순수 로컬 용량 문제라, "분석 자체의 반복
        // 실패"와 달리 배달 횟수 상한(이 테스트 기준 3)과 무관하게 항상 재시도 대상이어야 한다.
        ExecutorService fullExecutor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1)
        );
        CountDownLatch neverCompletes = new CountDownLatch(1);
        try {
            fullExecutor.submit(() -> {
                neverCompletes.await();
                return null;
            });
            fullExecutor.submit(() -> {
                neverCompletes.await();
                return null;
            });

            when(coordinator.reclaim(eq(1L), anyString())).thenReturn(VisionAttemptOutcome.COMMITTED);

            // maxDeliveryAttempts(3)를 넘어 5번 배달돼도 매번 executor가 가득 차 있으면
            // 최종 실패로 확정되면 안 된다.
            for (long deliveryCount = 1; deliveryCount <= 5; deliveryCount++) {
                newConsumer(fullExecutor).processReclaimed(recordFor(1L, List.of("https://example.com/a.jpg")), deliveryCount);
            }

            verify(failureRecorder, never()).recordVisionFailure(any(), any(), any());
            verify(redisTemplate, never()).opsForStream();
        } finally {
            neverCompletes.countDown();
            fullExecutor.shutdownNow();
        }
    }

    @Test
    void executor_포화가_5회_연속_반복돼도_여유가_생기면_그다음_시도는_정상_처리된다() throws InterruptedException {
        ExecutorService executor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1)
        );
        CountDownLatch blockingTasksMayFinish = new CountDownLatch(1);
        try {
            // 1) executor를 가득 채워 5회 연속 배달이 전부 RejectedExecutionException으로 거부되게 한다.
            executor.submit(() -> {
                blockingTasksMayFinish.await();
                return null;
            });
            executor.submit(() -> {
                blockingTasksMayFinish.await();
                return null;
            });

            when(coordinator.reclaim(eq(1L), anyString())).thenReturn(VisionAttemptOutcome.COMMITTED);
            for (long deliveryCount = 1; deliveryCount <= 5; deliveryCount++) {
                newConsumer(executor).processReclaimed(recordFor(1L, List.of("https://example.com/a.jpg")), deliveryCount);
            }
            verify(failureRecorder, never()).recordVisionFailure(any(), any(), any());
            verify(redisTemplate, never()).opsForStream();

            // 2) 막고 있던 작업을 풀어 executor에 다시 여유가 생기게 한다.
            blockingTasksMayFinish.countDown();
            // ThreadPoolExecutor(1,1,...)의 유일한 스레드가 막힌 작업들을 순서대로 비우는 동안
            // 대기해, 다음 submit()이 확실히 여유 슬롯을 잡게 한다.
            Thread.sleep(200);

            // 3) 여유가 생긴 뒤의 6번째 시도는 정상적으로 Vision을 호출하고 완료해야 한다.
            when(redisTemplate.opsForStream()).thenReturn(streamOperations);
            when(visionAnalysisService.analyze(any())).thenReturn(sampleResult());
            when(coordinator.complete(eq(1L), anyString(), anyString())).thenReturn(VisionAttemptOutcome.COMMITTED);

            newConsumer(executor).processReclaimed(recordFor(1L, List.of("https://example.com/a.jpg")), 6L);

            verify(visionAnalysisService).analyze(any());
            verify(streamOperations).acknowledge(eq(properties.getKey()), eq(properties.getGroup()), eq(RecordId.of("1-0")));
        } finally {
            blockingTasksMayFinish.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void executor_거부_5회_후_여유가_생겨_처음_Vision을_호출했다가_일시_오류가_나도_곧바로_최종_실패로_확정되지_않는다() throws InterruptedException {
        // executor 거부 5회는 Vision을 아예 시도조차 못한 것이라 실제 시도 횟수(visionFailureAttemptCount)를
        // 전혀 소모하지 않아야 한다. 그래서 여유가 생긴 뒤 처음으로 실제 Vision을 호출했다가 일시
        // 오류(5xx)가 나도, 그것이 "1번째" 실제 실패로 기록돼 재시도 여지가 남아있어야 한다 -
        // Redis 배달 횟수(이미 6번째)를 기준으로 삼았다면 곧바로 상한을 넘겨 최종 실패로 확정됐을 것이다.
        ExecutorService executor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1)
        );
        CountDownLatch blockingTasksMayFinish = new CountDownLatch(1);
        try {
            executor.submit(() -> {
                blockingTasksMayFinish.await();
                return null;
            });
            executor.submit(() -> {
                blockingTasksMayFinish.await();
                return null;
            });

            when(coordinator.reclaim(eq(1L), anyString())).thenReturn(VisionAttemptOutcome.COMMITTED);
            for (long deliveryCount = 1; deliveryCount <= 5; deliveryCount++) {
                newConsumer(executor).processReclaimed(recordFor(1L, List.of("https://example.com/a.jpg")), deliveryCount);
            }
            verify(coordinator, never()).incrementFailureAttempt(any(), any());
            verify(failureRecorder, never()).recordVisionFailure(any(), any(), any());
            verify(redisTemplate, never()).opsForStream();

            blockingTasksMayFinish.countDown();
            Thread.sleep(200); // 유일한 스레드가 막힌 작업들을 비우는 동안 대기

            // 여유가 생긴 뒤 6번째 배달에서 처음으로 실제 Vision을 호출하지만 일시 오류가 난다.
            when(visionAnalysisService.analyze(any())).thenThrow(retryableHttpError());
            when(coordinator.incrementFailureAttempt(eq(1L), anyString()))
                    .thenReturn(new VisionFailureAttemptResult(VisionAttemptOutcome.COMMITTED, 1)); // 진짜 실패는 이번이 처음(1회차)

            newConsumer(executor).processReclaimed(recordFor(1L, List.of("https://example.com/a.jpg")), 6L);

            verify(visionAnalysisService).analyze(any()); // 이번에는 실제로 호출됐다
            verify(failureRecorder, never()).recordVisionFailure(any(), any(), any()); // 그런데도 최종 실패로 확정되지 않음
            verify(redisTemplate, never()).opsForStream(); // ACK도 하지 않음 - 다음 회수를 기다린다
        } finally {
            blockingTasksMayFinish.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void Vision_실패_기록_저장_자체가_실패하면_ACK하지_않는다() {
        when(coordinator.claim(eq(1L), anyString())).thenReturn(VisionAttemptOutcome.COMMITTED);
        when(visionAnalysisService.analyze(any())).thenThrow(nonRetryableHttpError());
        doThrow(new RuntimeException("DB 오류")).when(failureRecorder)
                .recordVisionFailure(anyLong(), anyString(), anyString());

        newConsumer().onMessage(recordFor(1L, List.of("https://example.com/a.jpg")));

        verify(redisTemplate, never()).opsForStream();
    }

    @Test
    void Vision_실패_기록_시_소유권을_상실하면_ACK하지_않는다() {
        when(coordinator.claim(eq(1L), anyString())).thenReturn(VisionAttemptOutcome.COMMITTED);
        when(visionAnalysisService.analyze(any())).thenThrow(nonRetryableHttpError());
        when(failureRecorder.recordVisionFailure(eq(1L), anyString(), anyString()))
                .thenReturn(VisionAttemptOutcome.OWNERSHIP_LOST);

        newConsumer().onMessage(recordFor(1L, List.of("https://example.com/a.jpg")));

        verify(redisTemplate, never()).opsForStream();
    }

    // ---------- 실패 Stream 발행 ----------

    @Test
    void 최종_실패_기록_후_실패_이벤트_발행과_플래그_갱신까지_성공하면_ACK한다() {
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        when(coordinator.claim(eq(1L), anyString())).thenReturn(VisionAttemptOutcome.COMMITTED);
        when(visionAnalysisService.analyze(any())).thenThrow(nonRetryableHttpError());
        when(failureRecorder.recordVisionFailure(eq(1L), anyString(), anyString()))
                .thenReturn(VisionAttemptOutcome.COMMITTED);
        VisionFailureEvent event = new VisionFailureEvent(1L, "VISION", "잘못된 요청", 0L);
        when(failureStreamRecorder.pendingFailureEvent(1L)).thenReturn(Optional.of(event));

        newConsumer().onMessage(recordFor(1L, List.of("https://example.com/a.jpg")));

        verify(failureStreamProducer).publish(event);
        verify(failureStreamRecorder).markPublished(1L);
        verify(streamOperations).acknowledge(eq(properties.getKey()), eq(properties.getGroup()), eq(RecordId.of("1-0")));
    }

    @Test
    void 실패_이벤트_발행_자체가_실패하면_ACK하지_않는다() {
        when(coordinator.claim(eq(1L), anyString())).thenReturn(VisionAttemptOutcome.COMMITTED);
        when(visionAnalysisService.analyze(any())).thenThrow(nonRetryableHttpError());
        when(failureRecorder.recordVisionFailure(eq(1L), anyString(), anyString()))
                .thenReturn(VisionAttemptOutcome.COMMITTED);
        VisionFailureEvent event = new VisionFailureEvent(1L, "VISION", "잘못된 요청", 0L);
        when(failureStreamRecorder.pendingFailureEvent(1L)).thenReturn(Optional.of(event));
        doThrow(new RuntimeException("Redis 연결 실패")).when(failureStreamProducer).publish(event);

        newConsumer().onMessage(recordFor(1L, List.of("https://example.com/a.jpg")));

        verify(failureStreamRecorder, never()).markPublished(any());
        verify(redisTemplate, never()).opsForStream();
    }

    @Test
    void 실패_이벤트_발행은_성공했지만_플래그_갱신이_실패하면_ACK하지_않는다() {
        when(coordinator.claim(eq(1L), anyString())).thenReturn(VisionAttemptOutcome.COMMITTED);
        when(visionAnalysisService.analyze(any())).thenThrow(nonRetryableHttpError());
        when(failureRecorder.recordVisionFailure(eq(1L), anyString(), anyString()))
                .thenReturn(VisionAttemptOutcome.COMMITTED);
        VisionFailureEvent event = new VisionFailureEvent(1L, "VISION", "잘못된 요청", 0L);
        when(failureStreamRecorder.pendingFailureEvent(1L)).thenReturn(Optional.of(event));
        doThrow(new RuntimeException("DB 오류")).when(failureStreamRecorder).markPublished(1L);

        newConsumer().onMessage(recordFor(1L, List.of("https://example.com/a.jpg")));

        verify(failureStreamProducer).publish(event); // XADD는 나갔다 - 재전달 시 중복 발행 가능(at-least-once)
        verify(redisTemplate, never()).opsForStream();
    }

    @Test
    void DB_완료_후_ACK만_실패해_재전달된_실패_메시지는_미발행이면_다시_발행한_뒤_ACK한다() {
        // claim/reclaim이 ALREADY_FINALIZED를 반환한 경우(이미 VISION_FAILED) - 이전 시도가 DB
        // 커밋에는 성공했지만 발행 또는 ACK 전에 죽은 상황을 흉내낸다.
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        when(coordinator.claim(eq(1L), anyString())).thenReturn(VisionAttemptOutcome.ALREADY_FINALIZED);
        VisionFailureEvent event = new VisionFailureEvent(1L, "VISION", "이전 시도의 실패", 0L);
        when(failureStreamRecorder.pendingFailureEvent(1L)).thenReturn(Optional.of(event));

        newConsumer().onMessage(recordFor(1L, List.of("https://example.com/a.jpg")));

        verify(visionAnalysisService, never()).analyze(any());
        verify(failureStreamProducer).publish(event);
        verify(failureStreamRecorder).markPublished(1L);
        verify(streamOperations).acknowledge(eq(properties.getKey()), eq(properties.getGroup()), eq(RecordId.of("1-0")));
    }

    @Test
    void 이미_발행된_실패는_재전달돼도_다시_발행하지_않고_ACK한다() {
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        when(coordinator.claim(eq(1L), anyString())).thenReturn(VisionAttemptOutcome.ALREADY_FINALIZED);
        when(failureStreamRecorder.pendingFailureEvent(1L)).thenReturn(Optional.empty()); // 이미 발행됨

        newConsumer().onMessage(recordFor(1L, List.of("https://example.com/a.jpg")));

        verify(failureStreamProducer, never()).publish(any());
        verify(streamOperations).acknowledge(eq(properties.getKey()), eq(properties.getGroup()), eq(RecordId.of("1-0")));
    }

    @Test
    void 정상_완료로_이미_종료된_메시지는_실패_Stream과_무관하게_ACK한다() {
        // claim이 ALREADY_FINALIZED를 반환했지만 VISION_FAILED가 아닌 정상 완료 상태 - pendingFailureEvent가
        // empty를 반환하도록(=발행 관심 없음) 이미 setUp()에서 기본 스텁돼 있다.
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        when(coordinator.claim(eq(1L), anyString())).thenReturn(VisionAttemptOutcome.ALREADY_FINALIZED);

        newConsumer().onMessage(recordFor(1L, List.of("https://example.com/a.jpg")));

        verify(failureStreamProducer, never()).publish(any());
        verify(streamOperations).acknowledge(eq(properties.getKey()), eq(properties.getGroup()), eq(RecordId.of("1-0")));
    }

    @Test
    void 신규_배달인데_소유권을_확보하지_못하면_Vision을_호출하지_않고_ACK하지_않는다() {
        // 정상적으로는 도달하지 않는 방어적 경로(claim 대상은 항상 QUEUED뿐이라 경합이 없어야 함).
        when(coordinator.claim(eq(1L), anyString())).thenReturn(VisionAttemptOutcome.OWNERSHIP_LOST);

        newConsumer().onMessage(recordFor(1L, List.of("https://example.com/a.jpg")));

        verify(visionAnalysisService, never()).analyze(any());
        verify(redisTemplate, never()).opsForStream();
    }

    @Test
    void claim_저장_자체가_실패하면_ACK하지_않는다() {
        when(coordinator.claim(eq(1L), anyString())).thenThrow(new RuntimeException("DB 연결 실패"));

        newConsumer().onMessage(recordFor(1L, List.of("https://example.com/a.jpg")));

        verify(visionAnalysisService, never()).analyze(any());
        verify(redisTemplate, never()).opsForStream();
    }

    @Test
    void Vision_결과_저장이_실패하면_ACK하지_않는다() {
        when(coordinator.claim(eq(1L), anyString())).thenReturn(VisionAttemptOutcome.COMMITTED);
        when(visionAnalysisService.analyze(any())).thenReturn(sampleResult());
        when(coordinator.complete(eq(1L), anyString(), anyString())).thenThrow(new RuntimeException("DB 연결 실패"));

        newConsumer().onMessage(recordFor(1L, List.of("https://example.com/a.jpg")));

        verify(redisTemplate, never()).opsForStream();
    }

    @Test
    void Vision_완료_시_소유권을_상실하면_ACK하지_않는다() {
        // A가 claim해 Vision을 호출하는 동안 B가 재선점한 뒤, A가 새 트랜잭션에서 재조회해
        // completeVision을 시도하는 상황(VisionAttemptCoordinator가 OWNERSHIP_LOST로 판정).
        when(coordinator.claim(eq(1L), anyString())).thenReturn(VisionAttemptOutcome.COMMITTED);
        when(visionAnalysisService.analyze(any())).thenReturn(sampleResult());
        when(coordinator.complete(eq(1L), anyString(), anyString())).thenReturn(VisionAttemptOutcome.OWNERSHIP_LOST);

        newConsumer().onMessage(recordFor(1L, List.of("https://example.com/a.jpg")));

        verify(redisTemplate, never()).opsForStream();
    }

    @Test
    void 메시지_파싱이_안되면_소유권_확보조차_안_하고_ACK하지_않는다() {
        MapRecord<String, String, String> malformed = StreamRecords.<String, String, String>mapBacked(Map.of("payload", "이건 JSON이 아닙니다"))
                .withStreamKey(properties.getKey())
                .withId(RecordId.of("1-0"));

        newConsumer().onMessage(malformed);

        verify(coordinator, never()).claim(any(), any());
        verify(redisTemplate, never()).opsForStream();
    }

    // ---------- processReclaimed: PEL 회수 경로 ----------

    @Test
    void 일시_오류로_1차_시도가_실패해도_ACK되지_않고_남아있다가_재시도에서_성공하면_ACK한다() {
        // 1차 배달(deliveryCount=1): 재시도 가능한 오류 - ACK 안 함, 최종 실패 기록도 안 함.
        // 2차 배달(deliveryCount=2, PEL 회수를 통한 재시도): 같은 세션이 이번엔 성공.
        when(coordinator.reclaim(eq(1L), anyString())).thenReturn(VisionAttemptOutcome.COMMITTED);
        when(visionAnalysisService.analyze(any()))
                .thenThrow(retryableHttpError())
                .thenReturn(sampleResult());
        when(coordinator.incrementFailureAttempt(eq(1L), anyString()))
                .thenReturn(new VisionFailureAttemptResult(VisionAttemptOutcome.COMMITTED, 1));
        when(coordinator.complete(eq(1L), anyString(), anyString())).thenReturn(VisionAttemptOutcome.COMMITTED);

        AnalysisTaskConsumer consumer = newConsumer();
        consumer.processReclaimed(recordFor(1L, List.of("https://example.com/a.jpg")), 1L);
        verify(redisTemplate, never()).opsForStream();
        verify(failureRecorder, never()).recordVisionFailure(any(), any(), any());

        when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        consumer.processReclaimed(recordFor(1L, List.of("https://example.com/a.jpg")), 2L);

        verify(visionAnalysisService, org.mockito.Mockito.times(2)).analyze(any());
        verify(streamOperations).acknowledge(eq(properties.getKey()), eq(properties.getGroup()), eq(RecordId.of("1-0")));
    }

    @Test
    void 회수된_메시지는_reclaim_성공_시_Vision을_재시도하고_완료되면_ACK한다() {
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        when(coordinator.reclaim(eq(1L), anyString())).thenReturn(VisionAttemptOutcome.COMMITTED);
        when(visionAnalysisService.analyze(any())).thenReturn(sampleResult());
        when(coordinator.complete(eq(1L), anyString(), anyString())).thenReturn(VisionAttemptOutcome.COMMITTED);

        newConsumer().processReclaimed(recordFor(1L, List.of("https://example.com/a.jpg")), 2L);

        verify(visionAnalysisService).analyze(any());
        verify(streamOperations).acknowledge(eq(properties.getKey()), eq(properties.getGroup()), eq(RecordId.of("1-0")));
    }

    @Test
    void 회수_시점에_이미_정상_종료된_세션이면_Vision을_호출하지_않고_ACK한다() {
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        when(coordinator.reclaim(eq(1L), anyString())).thenReturn(VisionAttemptOutcome.ALREADY_FINALIZED);

        newConsumer().processReclaimed(recordFor(1L, List.of("https://example.com/a.jpg")), 2L);

        verify(visionAnalysisService, never()).analyze(any());
        verify(streamOperations).acknowledge(eq(properties.getKey()), eq(properties.getGroup()), eq(RecordId.of("1-0")));
    }

    @Test
    void 회수_시점에_다른_시도가_이미_소유권을_가지고_있으면_ACK하지_않는다() {
        when(coordinator.reclaim(eq(1L), anyString())).thenReturn(VisionAttemptOutcome.OWNERSHIP_LOST);

        newConsumer().processReclaimed(recordFor(1L, List.of("https://example.com/a.jpg")), 2L);

        verify(visionAnalysisService, never()).analyze(any());
        verify(redisTemplate, never()).opsForStream();
    }

    @Test
    void 회수된_메시지_파싱이_안되면_reclaim조차_시도하지_않고_ACK하지_않는다() {
        MapRecord<String, String, String> malformed = StreamRecords.<String, String, String>mapBacked(Map.of("payload", "이건 JSON이 아닙니다"))
                .withStreamKey(properties.getKey())
                .withId(RecordId.of("1-0"));

        newConsumer().processReclaimed(malformed, 2L);

        verify(coordinator, never()).reclaim(any(), any());
        verify(redisTemplate, never()).opsForStream();
    }

    // ---------- 종료 처리(in-flight 추적) ----------

    @Test
    void 처리_중에는_inFlightCount가_1이고_끝나면_0으로_돌아온다() throws InterruptedException {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        when(coordinator.claim(eq(1L), anyString())).thenReturn(VisionAttemptOutcome.COMMITTED);
        when(visionAnalysisService.analyze(any())).thenAnswer(invocation -> {
            entered.countDown();
            release.await();
            return sampleResult();
        });
        when(coordinator.complete(eq(1L), anyString(), anyString())).thenReturn(VisionAttemptOutcome.COMMITTED);

        AnalysisTaskConsumer consumer = newConsumer();
        Thread worker = new Thread(() -> consumer.onMessage(recordFor(1L, List.of("https://example.com/a.jpg"))));
        worker.start();

        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(consumer.getInFlightCount()).isEqualTo(1);

        release.countDown();
        worker.join(2000);

        assertThat(consumer.getInFlightCount()).isEqualTo(0);
    }
}
