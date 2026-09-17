package com.vintic.backend.analyze.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.analyze.domain.ProductAnalysisSession;
import com.vintic.backend.analyze.domain.ProductAnalysisSessionRepository;
import com.vintic.backend.analyze.service.AnalysisFailureRecorder;
import com.vintic.backend.common.exception.AnalysisQueueException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamInfo;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AnalysisStreamRecoveryTest {

    private static final RecordId RECORD_ID = RecordId.of("1-0");
    private static final String INSTANCE_PREFIX = "worker-this-instance";

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private StreamOperations<String, Object, Object> streams;
    @Mock
    private ProductAnalysisSessionRepository sessionRepository;
    @Mock
    private AnalysisFailureRecorder failureRecorder;
    @Mock
    private AnalysisTaskProducer producer;
    @Mock
    private RedisStreamConsumerConfig consumerConfig;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AnalysisStreamProperties properties = new AnalysisStreamProperties();
    private AnalysisStreamRecovery recovery;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForStream()).thenReturn(streams);
        when(consumerConfig.instanceConsumerPrefix()).thenReturn(INSTANCE_PREFIX);
        recovery = new AnalysisStreamRecovery(redisTemplate, properties, sessionRepository, failureRecorder,
                producer, objectMapper, consumerConfig);
    }

    private void pendingFor(Duration idle) {
        PendingMessage pending = new PendingMessage(
                RECORD_ID, Consumer.from(properties.getGroup(), "worker-dead-instance-0"), idle, 1L);
        when(streams.pending(eq(properties.getKey()), eq(properties.getGroup()), eq(Range.unbounded()), anyLong()))
                .thenReturn(new PendingMessages(properties.getGroup(), List.of(pending)));
    }

    private void claimReturns(String payload) {
        MapRecord<String, Object, Object> record = StreamRecords.<String, Object, Object>mapBacked(Map.of("payload", payload))
                .withStreamKey(properties.getKey())
                .withId(RECORD_ID);
        when(streams.claim(eq(properties.getKey()), eq(properties.getGroup()), anyString(), any(Duration.class), eq(RECORD_ID)))
                .thenReturn(List.of(record));
    }

    private String payloadFor(Long analysisId) throws Exception {
        return objectMapper.writeValueAsString(new AnalysisTaskMessage(analysisId, List.of("https://example.com/a.jpg"), null));
    }

    private ProductAnalysisSession queuedSession() {
        ProductAnalysisSession session = ProductAnalysisSession.create();
        session.markImageUploaded(List.of("https://example.com/a.jpg"));
        session.markQueued();
        return session;
    }

    private Duration staleIdle() {
        return properties.getRecovery().getPendingIdleTimeout().plusMinutes(1);
    }

    private void verifyAcknowledged() {
        verify(streams).acknowledge(properties.getKey(), properties.getGroup(), RECORD_ID);
    }

    @Test
    void 방치_시간이_기준보다_짧으면_가져오지_않는다() {
        // 아직 처리 중일 수 있는 메시지를 건드리면 멀쩡한 분석을 실패로 만든다.
        pendingFor(properties.getRecovery().getPendingIdleTimeout().minusMinutes(1));

        assertThat(recovery.reclaimStalePendingMessages()).isZero();

        verify(streams, never()).claim(anyString(), anyString(), anyString(), any(Duration.class), any(RecordId.class));
    }

    @Test
    void 분석을_시작하지_못한_QUEUED_세션은_다시_넣고_옛_메시지를_ACK한다() throws Exception {
        pendingFor(staleIdle());
        claimReturns(payloadFor(1L));
        when(sessionRepository.findById(1L)).thenReturn(Optional.of(queuedSession()));

        assertThat(recovery.reclaimStalePendingMessages()).isEqualTo(1);

        verify(producer).enqueue(any(AnalysisTaskMessage.class));
        verify(failureRecorder, never()).recordVisionFailure(any(), anyString());
        verifyAcknowledged();
        // 회수한 메시지는 이 인스턴스의 회수 전용 Consumer 이름으로 가져온다
        verify(streams).claim(properties.getKey(), properties.getGroup(), INSTANCE_PREFIX + "-recovery",
                properties.getRecovery().getPendingIdleTimeout(), RECORD_ID);
    }

    @Test
    void 처리_도중_멈춘_VISION_PROCESSING_세션은_실패로_기록하고_ACK한다() throws Exception {
        pendingFor(staleIdle());
        claimReturns(payloadFor(1L));
        ProductAnalysisSession session = queuedSession();
        session.startVisionProcessing();
        when(sessionRepository.findById(1L)).thenReturn(Optional.of(session));

        recovery.reclaimStalePendingMessages();

        // 자동 재시도는 하지 않는다 - 유료 Vision 호출이 반복될 수 있다.
        verify(producer, never()).enqueue(any());
        verify(failureRecorder).recordVisionFailure(any(), eq(AnalysisStreamRecovery.TIMEOUT_FAILURE_MESSAGE));
        verifyAcknowledged();
    }

    @Test
    void 이미_끝난_세션의_메시지는_ACK만_한다() throws Exception {
        pendingFor(staleIdle());
        claimReturns(payloadFor(1L));
        ProductAnalysisSession session = queuedSession();
        session.startVisionProcessing();
        session.completeVision("{}");
        when(sessionRepository.findById(1L)).thenReturn(Optional.of(session));

        recovery.reclaimStalePendingMessages();

        verify(producer, never()).enqueue(any());
        verify(failureRecorder, never()).recordVisionFailure(any(), anyString());
        verifyAcknowledged();
    }

    @Test
    void 세션이_없거나_파싱할_수_없는_메시지는_ACK하고_버린다() {
        pendingFor(staleIdle());
        claimReturns("이건 JSON이 아닙니다");

        recovery.reclaimStalePendingMessages();

        verify(sessionRepository, never()).findById(any());
        verifyAcknowledged();
    }

    @Test
    void 다시_넣기가_실패하면_ACK하지_않아_다음_주기에_다시_본다() throws Exception {
        pendingFor(staleIdle());
        claimReturns(payloadFor(1L));
        when(sessionRepository.findById(1L)).thenReturn(Optional.of(queuedSession()));
        doThrow(new AnalysisQueueException("Redis 적재 실패", null)).when(producer).enqueue(any());

        assertThat(recovery.reclaimStalePendingMessages()).isZero();

        verify(streams, never()).acknowledge(anyString(), anyString(), any(RecordId[].class));
    }

    @Test
    void 다른_인스턴스가_먼저_가져가면_아무것도_하지_않는다() {
        pendingFor(staleIdle());
        when(streams.claim(anyString(), anyString(), anyString(), any(Duration.class), any(RecordId.class)))
                .thenReturn(List.of());

        assertThat(recovery.reclaimStalePendingMessages()).isZero();

        verify(sessionRepository, never()).findById(any());
    }

    @Test
    void 미처리가_없고_오래_조용한_다른_인스턴스_Consumer만_지운다() {
        long twoDaysMs = Duration.ofDays(2).toMillis();
        StreamInfo.XInfoConsumers consumers = new StreamInfo.XInfoConsumers(properties.getGroup(), List.of(
                List.of("name", "worker-dead-instance-0", "pending", 0L, "idle", twoDaysMs),
                List.of("name", "worker-dead-instance-1", "pending", 2L, "idle", twoDaysMs), // 미처리 있음
                List.of("name", "worker-busy-instance-0", "pending", 0L, "idle", 1_000L),   // 최근에 읽음
                List.of("name", INSTANCE_PREFIX + "-0", "pending", 0L, "idle", twoDaysMs)    // 이 인스턴스
        ));
        when(streams.consumers(properties.getKey(), properties.getGroup())).thenReturn(consumers);

        assertThat(recovery.removeIdleConsumers()).isEqualTo(1);

        verify(streams).deleteConsumer(properties.getKey(), Consumer.from(properties.getGroup(), "worker-dead-instance-0"));
    }

    @Test
    void Redis가_내려가_있어도_예약_작업이_예외를_던지지_않는다() {
        // 스케줄러 스레드를 경매 종료 배치와 같이 쓴다.
        when(streams.pending(anyString(), anyString(), any(Range.class), anyLong()))
                .thenThrow(new RuntimeException("Connection refused"));

        assertThatCode(() -> recovery.recover()).doesNotThrowAnyException();
    }
}
