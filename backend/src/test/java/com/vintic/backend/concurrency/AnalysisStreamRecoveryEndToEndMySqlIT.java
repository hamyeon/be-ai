package com.vintic.backend.concurrency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.ai.vision.dto.ConditionGrade;
import com.vintic.backend.ai.vision.dto.VisionAnalysisResult;
import com.vintic.backend.ai.vision.service.VisionAnalysisService;
import com.vintic.backend.analyze.domain.AnalysisStatus;
import com.vintic.backend.analyze.domain.ProductAnalysisSession;
import com.vintic.backend.analyze.domain.ProductAnalysisSessionRepository;
import com.vintic.backend.analyze.queue.AnalysisStreamMetrics;
import com.vintic.backend.analyze.queue.AnalysisStreamProperties;
import com.vintic.backend.analyze.queue.AnalysisStreamRecoveryProperties;
import com.vintic.backend.analyze.queue.AnalysisStreamRecoveryScheduler;
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
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// 죽은 Worker가 ACK하지 않은 메시지를 PEL 회수 스케줄러가 실제 Redis(XPENDING+XCLAIM)로 회수해,
// 실제 MySQL에 완료 상태를 커밋하고 실제 XACK까지 이어지는 전체 경로를 검증한다.
// 기존 테스트는 이 세 구간(Redis 회수 / DB 완료 / ACK)을 각각 mock으로 나눠서만 검증했고,
// 셋을 실제로 잇는 테스트가 없었다.
//
// VisionAnalysisService(AI팀 소유 로직)는 mock으로 대체한다 - 이 테스트의 관심사는 Vision
// 분석 결과 자체가 아니라 회수-완료-ACK 배선이 실제로 맞는지다.
//
// 운영 스트림 키/그룹과 겹치지 않도록 테스트 전용 키를 쓰고, AnalysisTaskConsumer/
// AnalysisStreamRecoveryScheduler를 직접 생성해 앱이 기동시 띄우는 실제 리스너 컨테이너(다른
// 소비자 이름으로 도는)와 경합하지 않게 한다 - "죽은 Worker"는 별도 consumer 이름으로 직접
// XREADGROUP해서 ack하지 않은 채로 만든다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Testcontainers
class AnalysisStreamRecoveryEndToEndMySqlIT {

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
    private VisionFailureStreamProducer failureStreamProducer;

    @Autowired
    private StringRedisTemplate redisTemplate;

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

        testStreamKey = "test:recovery:e2e:" + UUID.randomUUID();
        streamProperties = new AnalysisStreamProperties();
        streamProperties.setKey(testStreamKey);
        streamProperties.setGroup("test-recovery-group");
        streamProperties.setConsumerPrefix("recovery-worker");
        redisTemplate.opsForStream().createGroup(testStreamKey, ReadOffset.from("0"), streamProperties.getGroup());

        visionExecutor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        if (testStreamKey != null) {
            redisTemplate.delete(testStreamKey);
        }
        visionExecutor.shutdownNow();
    }

    private Long queuedSessionId() {
        ProductAnalysisSession session = ProductAnalysisSession.create();
        session.markImageUploaded(List.of("https://example.com/a.jpg"));
        session.markQueued();
        return sessionRepository.save(session).getId();
    }

    @Test
    void 죽은_Worker가_ACK하지_않은_메시지를_회수_스케줄러가_회수해_DB_완료와_실제_XACK까지_마친다() throws Exception {
        Long sessionId = queuedSessionId();
        String payload = objectMapper.writeValueAsString(
                new AnalysisTaskMessage(sessionId, List.of("https://example.com/a.jpg"), null)
        );
        redisTemplate.opsForStream().add(
                org.springframework.data.redis.connection.stream.StreamRecords
                        .mapBacked(Map.of("payload", payload))
                        .withStreamKey(testStreamKey)
        );

        // "죽은 Worker"를 흉내낸다: 별도 consumer 이름으로 직접 XREADGROUP해서 PEL에만 남기고,
        // 이후 아무 것도 하지 않는다(ack 없음) - 실제 AnalysisTaskConsumer.onMessage()를 거치지 않는다.
        List<MapRecord<String, Object, Object>> delivered = redisTemplate.opsForStream().read(
                Consumer.from(streamProperties.getGroup(), "dead-worker-1"),
                StreamReadOptions.empty().count(10),
                StreamOffset.create(testStreamKey, ReadOffset.lastConsumed())
        );
        assertThat(delivered).hasSize(1);

        AnalysisStreamRecoveryProperties recoveryProperties = new AnalysisStreamRecoveryProperties();
        recoveryProperties.setEnabled(true);
        recoveryProperties.setMinIdleTimeMs(50);
        recoveryProperties.setBatchSize(10);
        Thread.sleep(100); // minIdleTime(50ms)이 지나도록 대기 - 이제 회수 대상이다

        VisionAnalysisService visionAnalysisService = mock(VisionAnalysisService.class);
        VisionAnalysisResult result = new VisionAnalysisResult(
                "Nike", "Dunk Low", "Panda", 270, "설명", ConditionGrade.B,
                true, 0.9, false, List.of(), List.of(), List.of(), List.of()
        );
        when(visionAnalysisService.analyze(any())).thenReturn(result);

        AnalysisVisionProcessingProperties visionProperties = new AnalysisVisionProcessingProperties();
        visionProperties.setOverallTimeoutMs(10_000L);

        AnalysisStreamMetrics metrics = new AnalysisStreamMetrics(new SimpleMeterRegistry());
        AnalysisTaskConsumer consumer = new AnalysisTaskConsumer(
                visionAnalysisService, coordinator, failureRecorder, failureStreamRecorder, failureStreamProducer,
                new VisionFailureClassifier(), metrics, objectMapper, redisTemplate,
                streamProperties, visionProperties, visionExecutor
        );
        AnalysisStreamRecoveryScheduler scheduler = new AnalysisStreamRecoveryScheduler(
                redisTemplate, streamProperties, recoveryProperties, consumer, metrics
        );

        scheduler.scanAndReclaim();

        ProductAnalysisSession reloaded = sessionRepository.findById(sessionId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AnalysisStatus.AWAITING_USER_CONFIRMATION);
        assertThat(reloaded.getVisionResultJson()).contains("\"brand\":\"Nike\"");

        // 실제 XACK까지 이어졌는지 - PEL이 비어 있어야 한다(더 이상 dead-worker-1 소유로도 남아있지 않음).
        PendingMessages pending = redisTemplate.opsForStream()
                .pending(testStreamKey, streamProperties.getGroup(), Range.unbounded(), 10);
        assertThat(pending.size()).isEqualTo(0);
    }
}
