package com.vintic.backend.analyze.queue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// refreshPendingMetrics()의 "가장 오래된 idle" 조회(COUNT=1)와 scanOnce()의 회수 후보 조회
// (COUNT=batchSize)가 같은 pending(key, group, Range, count) 오버로드를 서로 다른 count로 부르므로,
// 스텁을 COUNT 값으로 명확히 구분한다(any-matcher로 뭉치면 두 호출이 서로의 순차 응답을 가로챈다).
@ExtendWith(MockitoExtension.class)
class AnalysisStreamRecoverySchedulerTest {

    private static final long BATCH_SIZE = 10L;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private StreamOperations<String, Object, Object> streamOperations;

    @Mock
    private AnalysisTaskConsumer analysisTaskConsumer;

    private final AnalysisStreamProperties streamProperties = new AnalysisStreamProperties();
    private final AnalysisStreamRecoveryProperties recoveryProperties = new AnalysisStreamRecoveryProperties();
    private final AnalysisStreamMetrics metrics = new AnalysisStreamMetrics(new SimpleMeterRegistry());

    @BeforeEach
    void setUp() {
        recoveryProperties.setEnabled(true);
        recoveryProperties.setMinIdleTimeMs(1000);
        recoveryProperties.setBatchSize((int) BATCH_SIZE);
        lenient().when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        // 게이지 갱신용 조회는 이 테스트들의 관심사가 아니므로 기본값을 비워둔다.
        lenient().when(streamOperations.pending(streamProperties.getKey(), streamProperties.getGroup()))
                .thenReturn(new PendingMessagesSummary(streamProperties.getGroup(), 0, Range.unbounded(), Map.of()));
        lenient().when(streamOperations.pending(eq(streamProperties.getKey()), eq(streamProperties.getGroup()), any(Range.class), eq(1L)))
                .thenReturn(new PendingMessages(streamProperties.getGroup(), List.of()));
    }

    private AnalysisStreamRecoveryScheduler newScheduler() {
        return new AnalysisStreamRecoveryScheduler(redisTemplate, streamProperties, recoveryProperties, analysisTaskConsumer, metrics);
    }

    private PendingMessage pendingMessage(String id, long idleMs, long deliveryCount) {
        return new PendingMessage(
                RecordId.of(id),
                org.springframework.data.redis.connection.stream.Consumer.from(streamProperties.getGroup(), "dead-worker"),
                Duration.ofMillis(idleMs), deliveryCount
        );
    }

    private void stubScanCandidates(PendingMessage... first) {
        when(streamOperations.pending(eq(streamProperties.getKey()), eq(streamProperties.getGroup()), any(Range.class), eq(BATCH_SIZE)))
                .thenReturn(
                        new PendingMessages(streamProperties.getGroup(), List.of(first)),
                        new PendingMessages(streamProperties.getGroup(), List.of())
                );
    }

    @Test
    void 회수_대상_후보를_XCLAIM으로_회수해_배달_횟수와_함께_넘겨준다() {
        stubScanCandidates(pendingMessage("1-0", 5000, 2));

        MapRecord<String, String, String> claimedRecord =
                StreamRecords.<String, String, String>mapBacked(Map.of("payload", "x")).withStreamKey(streamProperties.getKey())
                        .withId(RecordId.of("1-0"));
        doReturn(List.of(claimedRecord)).when(streamOperations)
                .claim(eq(streamProperties.getKey()), eq(streamProperties.getGroup()), any(), any(Duration.class), any(RecordId.class));

        newScheduler().scanAndReclaim();

        // XPENDING의 deliveryCount(2) + 이번 회수 자체(1) = 3
        verify(analysisTaskConsumer).processReclaimed(claimedRecord, 3L);
    }

    @Test
    void idle_기준을_안_넘긴_후보는_회수하지_않는다() {
        stubScanCandidates(pendingMessage("1-0", 100, 1)); // minIdleTime(1000ms) 미달

        newScheduler().scanAndReclaim();

        verify(streamOperations, never()).claim(any(), any(), any(), any(Duration.class), any(RecordId.class));
        verify(analysisTaskConsumer, never()).processReclaimed(any(), anyLong());
    }

    @Test
    void 다른_인스턴스가_먼저_회수하면_processReclaimed를_호출하지_않는다() {
        stubScanCandidates(pendingMessage("1-0", 5000, 1));
        doReturn(List.of()).when(streamOperations)
                .claim(eq(streamProperties.getKey()), eq(streamProperties.getGroup()), any(), any(Duration.class), any(RecordId.class));

        newScheduler().scanAndReclaim();

        verify(analysisTaskConsumer, never()).processReclaimed(any(), anyLong());
    }

    @Test
    void enabled가_false면_스캔을_시도하지_않는다() {
        recoveryProperties.setEnabled(false);

        newScheduler().scanAndReclaim();

        verify(redisTemplate, never()).opsForStream();
    }

    @Test
    void 종료_신호_이후에는_새_스캔을_시작하지_않는다() {
        AnalysisStreamRecoveryScheduler scheduler = newScheduler();
        scheduler.stopAcceptingNewScans();

        scheduler.scanAndReclaim();

        verify(redisTemplate, never()).opsForStream();
    }

    // ---------- Redis 연결 장애 ----------

    @Test
    void XPENDING_조회_자체가_실패하면_Redis_오류로_기록하고_회수_스캔은_그대로_진행한다() {
        when(streamOperations.pending(streamProperties.getKey(), streamProperties.getGroup()))
                .thenThrow(new RedisConnectionFailureException("연결 끊김"));
        when(streamOperations.pending(eq(streamProperties.getKey()), eq(streamProperties.getGroup()), any(Range.class), eq(BATCH_SIZE)))
                .thenReturn(new PendingMessages(streamProperties.getGroup(), List.of()));

        newScheduler().scanAndReclaim(); // 예외가 밖으로 새지 않아야 한다

        verify(analysisTaskConsumer, never()).processReclaimed(any(), anyLong());
    }

    @Test
    void 회수_스캔_중_Redis_오류가_발생해도_예외가_새지_않는다() {
        when(streamOperations.pending(eq(streamProperties.getKey()), eq(streamProperties.getGroup()), any(Range.class), eq(BATCH_SIZE)))
                .thenThrow(new RedisConnectionFailureException("연결 끊김"));

        newScheduler().scanAndReclaim(); // 예외가 새지 않고 조용히 로그만 남겨야 한다

        verify(analysisTaskConsumer, never()).processReclaimed(any(), anyLong());
    }

    @Test
    void Redis_연결이_반복적으로_실패해도_다음_스캔에서_정상화되면_다시_회수한다() {
        MapRecord<String, String, String> claimedRecord =
                StreamRecords.<String, String, String>mapBacked(Map.of("payload", "x")).withStreamKey(streamProperties.getKey())
                        .withId(RecordId.of("1-0"));
        when(streamOperations.pending(eq(streamProperties.getKey()), eq(streamProperties.getGroup()), any(Range.class), eq(BATCH_SIZE)))
                .thenThrow(new RedisConnectionFailureException("연결 끊김"))
                .thenReturn(
                        new PendingMessages(streamProperties.getGroup(), List.of(pendingMessage("1-0", 5000, 1))),
                        new PendingMessages(streamProperties.getGroup(), List.of())
                );
        doReturn(List.of(claimedRecord)).when(streamOperations)
                .claim(eq(streamProperties.getKey()), eq(streamProperties.getGroup()), any(), any(Duration.class), any(RecordId.class));

        AnalysisStreamRecoveryScheduler scheduler = newScheduler();
        scheduler.scanAndReclaim(); // 1차: 오류
        scheduler.scanAndReclaim(); // 2차: 정상화되어 회수 성공

        verify(analysisTaskConsumer, times(1)).processReclaimed(claimedRecord, 2L);
    }
}
