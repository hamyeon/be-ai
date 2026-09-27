package com.vintic.backend.concurrency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.ai.vision.service.VisionAnalysisService;
import com.vintic.backend.analyze.domain.AnalysisFailureStage;
import com.vintic.backend.analyze.domain.AnalysisStatus;
import com.vintic.backend.analyze.domain.ProductAnalysisSession;
import com.vintic.backend.analyze.domain.ProductAnalysisSessionRepository;
import com.vintic.backend.analyze.queue.AnalysisStreamMetrics;
import com.vintic.backend.analyze.queue.AnalysisStreamProperties;
import com.vintic.backend.analyze.queue.AnalysisTaskConsumer;
import com.vintic.backend.analyze.queue.AnalysisTaskMessage;
import com.vintic.backend.analyze.queue.AnalysisVisionProcessingProperties;
import com.vintic.backend.analyze.queue.VisionFailureClassifier;
import com.vintic.backend.analyze.queue.VisionFailureStreamProducer;
import com.vintic.backend.analyze.service.AnalysisFailureRecorder;
import com.vintic.backend.analyze.service.VisionAttemptCoordinator;
import com.vintic.backend.analyze.service.VisionFailureStreamRecorder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

// 실패 Stream 발행 구간을 실제 MySQL(vision_failure_stream_published 플래그) + 실제 Redis(XADD)로
// 검증한다. "VISION_FAILED 커밋 후 XADD/플래그 갱신 전에 죽거나 실패한 뒤 재전달된 경우, 발행
// 없이 곧바로 ACK하면 안 되고, 미발행 상태를 확인해 다시 발행한 뒤에만 ACK한다"는 요구사항이
// 실제로 지켜지는지가 핵심이다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Testcontainers
class VisionFailureStreamMySqlIT {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired
    private ProductAnalysisSessionRepository sessionRepository;

    @Autowired
    private VisionAttemptCoordinator coordinator;

    @Autowired
    private AnalysisFailureRecorder failureRecorder;

    @Autowired
    private VisionFailureStreamRecorder failureStreamRecorder;

    @Autowired
    private StringRedisTemplate redisTemplate;

    // VisionFailureStreamProducer는 일부러 autowire하지 않는다 - 스프링이 관리하는 빈은 운영
    // 기본 failure-key(ai:analysis:failures)에 바인딩돼 있어, 이 테스트가 쓰는 격리된 테스트
    // 키(streamProperties.getFailureKey())로 실제 XADD가 나가지 않는다. 같은 AnalysisTaskConsumer
    // 생성자에 넘길 것과 동일하게, 테스트 전용 streamProperties로 직접 만든다.
    private VisionFailureStreamProducer failureStreamProducer;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private String testStreamKey;
    private AnalysisStreamProperties streamProperties;
    private ExecutorService visionExecutor;

    @BeforeEach
    void setUp() {
        boolean redisAvailable;
        try {
            redisAvailable = "PONG".equalsIgnoreCase(
                    String.valueOf(redisTemplate.getConnectionFactory().getConnection().ping())
            );
        } catch (Exception e) {
            redisAvailable = false;
        }
        assumeTrue(redisAvailable, "로컬 Redis에 연결할 수 없어 이 통합 테스트를 건너뜁니다.");

        testStreamKey = "test:failure-stream:" + UUID.randomUUID();
        streamProperties = new AnalysisStreamProperties();
        streamProperties.setKey(testStreamKey);
        streamProperties.setGroup("test-failure-group");
        streamProperties.setFailureKey("test:failures:" + UUID.randomUUID());
        redisTemplate.opsForStream().createGroup(testStreamKey, ReadOffset.from("0"), streamProperties.getGroup());
        failureStreamProducer = new VisionFailureStreamProducer(redisTemplate, objectMapper, streamProperties);

        visionExecutor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        redisTemplate.delete(testStreamKey);
        redisTemplate.delete(streamProperties.getFailureKey());
        visionExecutor.shutdownNow();
    }

    private AnalysisTaskConsumer newConsumer() {
        AnalysisVisionProcessingProperties visionProperties = new AnalysisVisionProcessingProperties();
        return new AnalysisTaskConsumer(
                mock(VisionAnalysisService.class), coordinator, failureRecorder, failureStreamRecorder, failureStreamProducer,
                new VisionFailureClassifier(), new AnalysisStreamMetrics(new SimpleMeterRegistry()), objectMapper, redisTemplate,
                streamProperties, visionProperties, visionExecutor
        );
    }

    @Test
    void VISION_FAILED_커밋_후_발행_전에_재전달되면_미발행_상태를_확인해_다시_발행한_뒤_ACK한다() throws Exception {
        // 1) 이전 시도가 VISION_FAILED까지는 커밋했지만(claim -> failVision), 발행/ACK 전에
        //    죽었다고 가정한다 - visionFailureStreamPublished는 기본값 false로 남아있다.
        ProductAnalysisSession session = ProductAnalysisSession.create();
        session.markImageUploaded(List.of("https://example.com/a.jpg"));
        session.markQueued();
        Long sessionId = sessionRepository.save(session).getId();

        String firstToken = UUID.randomUUID().toString();
        coordinator.claim(sessionId, firstToken);
        failureRecorder.recordVisionFailure(sessionId, firstToken, "OpenAI 호출 실패");

        ProductAnalysisSession beforeRedelivery = sessionRepository.findById(sessionId).orElseThrow();
        assertThat(beforeRedelivery.getStatus()).isEqualTo(AnalysisStatus.VISION_FAILED);
        assertThat(beforeRedelivery.isVisionFailureStreamPublished()).isFalse();

        // 2) 같은 메시지가 재전달됐다고 가정한다(XACK만 실패했거나, 회수 스케줄러가 다시 넘긴 상황).
        String payload = objectMapper.writeValueAsString(
                new AnalysisTaskMessage(sessionId, List.of("https://example.com/a.jpg"), null)
        );
        redisTemplate.opsForStream().add(
                StreamRecords.mapBacked(Map.of("payload", payload)).withStreamKey(testStreamKey)
        );
        List<MapRecord<String, Object, Object>> delivered = redisTemplate.opsForStream().read(
                Consumer.from(streamProperties.getGroup(), "worker-2"),
                StreamReadOptions.empty().count(10),
                StreamOffset.create(testStreamKey, ReadOffset.lastConsumed())
        );
        assertThat(delivered).hasSize(1);
        RecordId recordId = delivered.get(0).getId();
        MapRecord<String, String, String> redeliveredRecord =
                StreamRecords.<String, String, String>mapBacked(Map.of("payload", payload))
                        .withStreamKey(testStreamKey).withId(recordId);

        newConsumer().onMessage(redeliveredRecord);

        // 3) 미발행 상태를 확인해 실제로 발행하고, 플래그를 갱신한 뒤에만 ACK했어야 한다.
        ProductAnalysisSession afterRedelivery = sessionRepository.findById(sessionId).orElseThrow();
        assertThat(afterRedelivery.isVisionFailureStreamPublished()).isTrue();

        List<MapRecord<String, Object, Object>> failureEvents = redisTemplate.opsForStream().range(
                streamProperties.getFailureKey(), Range.unbounded()
        );
        assertThat(failureEvents).hasSize(1);
        assertThat(String.valueOf(failureEvents.get(0).getValue().get("payload")))
                .contains("\"analysisId\":" + sessionId)
                .contains(AnalysisFailureStage.VISION.name());

        PendingMessages pending = redisTemplate.opsForStream()
                .pending(testStreamKey, streamProperties.getGroup(), Range.unbounded(), 10);
        assertThat(pending.size()).isEqualTo(0); // ACK까지 끝났으므로 PEL이 비어 있어야 한다
    }

    @Test
    void 이미_발행된_실패는_재전달돼도_실패_Stream에_다시_쌓이지_않고_ACK된다() throws Exception {
        ProductAnalysisSession session = ProductAnalysisSession.create();
        session.markImageUploaded(List.of("https://example.com/a.jpg"));
        session.markQueued();
        Long sessionId = sessionRepository.save(session).getId();

        String token = UUID.randomUUID().toString();
        coordinator.claim(sessionId, token);
        failureRecorder.recordVisionFailure(sessionId, token, "OpenAI 호출 실패");
        failureStreamRecorder.markPublished(sessionId); // 이미 정상적으로 발행까지 끝난 상황을 흉내낸다

        String payload = objectMapper.writeValueAsString(
                new AnalysisTaskMessage(sessionId, List.of("https://example.com/a.jpg"), null)
        );
        redisTemplate.opsForStream().add(
                StreamRecords.mapBacked(Map.of("payload", payload)).withStreamKey(testStreamKey)
        );
        List<MapRecord<String, Object, Object>> delivered = redisTemplate.opsForStream().read(
                Consumer.from(streamProperties.getGroup(), "worker-2"),
                StreamReadOptions.empty().count(10),
                StreamOffset.create(testStreamKey, ReadOffset.lastConsumed())
        );
        RecordId recordId = delivered.get(0).getId();
        MapRecord<String, String, String> redeliveredRecord =
                StreamRecords.<String, String, String>mapBacked(Map.of("payload", payload))
                        .withStreamKey(testStreamKey).withId(recordId);

        newConsumer().onMessage(redeliveredRecord);

        List<MapRecord<String, Object, Object>> failureEvents = redisTemplate.opsForStream().range(
                streamProperties.getFailureKey(), Range.unbounded()
        );
        assertThat(failureEvents).isEmpty(); // 중복 발행 없음

        PendingMessages pending = redisTemplate.opsForStream()
                .pending(testStreamKey, streamProperties.getGroup(), Range.unbounded(), 10);
        assertThat(pending.size()).isEqualTo(0);
    }
}
