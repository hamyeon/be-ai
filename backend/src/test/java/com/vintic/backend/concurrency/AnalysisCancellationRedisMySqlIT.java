package com.vintic.backend.concurrency;

import com.vintic.backend.ai.vision.dto.VisionAnalysisResult;
import com.vintic.backend.ai.vision.service.VisionAnalysisService;
import com.vintic.backend.analyze.domain.ProductAnalysisSession;
import com.vintic.backend.analyze.domain.ProductAnalysisSessionRepository;
import com.vintic.backend.analyze.queue.AnalysisStreamProperties;
import com.vintic.backend.analyze.queue.AnalysisTaskConsumer;
import com.vintic.backend.analyze.queue.AnalysisTaskMessage;
import com.vintic.backend.analyze.queue.AnalysisTaskProducer;
import com.vintic.backend.analyze.service.ProductAnalyzeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// #127: AnalysisCancellationMySqlIT/AnalysisCancellationRegistrationRaceMySqlIT는
// VisionAttemptCoordinator/ProductAnalyzeService 메서드를 직접 호출해 "DB 행 잠금" 원자성만
// 검증했을 뿐, Redis는 전혀 쓰지 않았다(실제 XADD/XREADGROUP/XCLAIM/XACK가 한 번도 일어나지 않음).
// 이 클래스는 그 간극을 메운다 - 실제 Redis(Testcontainers)와 실제 MySQL, 그리고 Mock이 아닌
// 실제 AnalysisTaskConsumer/VisionAttemptCoordinator 빈을 그대로 써서, "취소된 세션의 메시지가
// 실제 Redis Stream을 통해 들어와도 Vision을 호출하지 않고 실제 XACK되는지"를 검증한다.
// VisionAnalysisService(AI 호출)만 Mock으로 대체한다 - 외부 AI 호출 자체는 이번 기능의 검증
// 대상이 아니다.
//
// analysis.stream.consumer.enabled=false로 앱 자신의 백그라운드 StreamMessageListenerContainer를
// 꺼서, 이 테스트가 직접 미는 메시지를 그 백그라운드 컨테이너가 동시에 집어가 결과가 섞이는
// 것을 막는다(AnalysisStreamRecoveryProperties.enabled는 기본값이 이미 false라 회수 스케줄러는
// 원래도 돌지 않는다).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Testcontainers
class AnalysisCancellationRedisMySqlIT {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        // application-local.yml: spring.data.redis.host/port = ${LOCAL_REDIS_HOST}/${LOCAL_REDIS_PORT}
        registry.add("LOCAL_REDIS_HOST", redis::getHost);
        registry.add("LOCAL_REDIS_PORT", () -> redis.getMappedPort(6379));
        registry.add("analysis.stream.consumer.enabled", () -> "false");
    }

    @MockitoBean
    private VisionAnalysisService visionAnalysisService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private AnalysisStreamProperties streamProperties;

    @Autowired
    private AnalysisTaskConsumer analysisTaskConsumer;

    @Autowired
    private AnalysisTaskProducer analysisTaskProducer;

    @Autowired
    private ProductAnalysisSessionRepository sessionRepository;

    @Autowired
    private ProductAnalyzeService productAnalyzeService;

    private static final Long USER_ID = 1L;

    @BeforeEach
    void setUp() {
        // 테스트마다 같은 기본 stream key를 재사용하므로, 이전 테스트가 남긴 엔트리/PEL과
        // 섞이지 않도록 매번 비우고 Consumer Group을 다시 만든다.
        redisTemplate.delete(streamProperties.getKey());
        try {
            redisTemplate.opsForStream().createGroup(streamProperties.getKey(), ReadOffset.from("0"), streamProperties.getGroup());
        } catch (Exception ignored) {
            // BUSYGROUP 등 - 무시(RedisStreamConsumerConfig.createConsumerGroupIfAbsent와 동일 원칙)
        }
    }

    private Long queuedSessionId() {
        ProductAnalysisSession session = ProductAnalysisSession.create(USER_ID);
        session.markImageUploaded(List.of("https://example.com/a.jpg"));
        session.markQueued();
        return sessionRepository.save(session).getId();
    }

    private long pendingCount() {
        return redisTemplate.opsForStream()
                .pending(streamProperties.getKey(), streamProperties.getGroup(), Range.unbounded(), 10)
                .size();
    }

    @SuppressWarnings("unchecked")
    private MapRecord<String, String, String> readOneFromRealRedis(String consumerName) {
        List<MapRecord<String, String, String>> records = (List<MapRecord<String, String, String>>) (List<?>) redisTemplate.opsForStream().read(
                Consumer.from(streamProperties.getGroup(), consumerName),
                StreamReadOptions.empty().count(10),
                StreamOffset.create(streamProperties.getKey(), ReadOffset.lastConsumed())
        );
        assertThat(records).hasSize(1);
        return records.get(0);
    }

    @Test
    void 대기중_세션을_취소한_뒤_실제_Redis로_새로_배달된_메시지를_처리하면_Vision을_호출하지_않고_실제_XACK된다() {
        Long sessionId = queuedSessionId();
        productAnalyzeService.cancel(sessionId, USER_ID);

        analysisTaskProducer.enqueue(new AnalysisTaskMessage(
                sessionId, List.of("https://example.com/a.jpg"), List.of("https://example.com/a.jpg")));
        MapRecord<String, String, String> delivered = readOneFromRealRedis("consumer-1");
        assertThat(pendingCount()).isEqualTo(1); // ack 전에는 PEL에 남아있어야 한다

        analysisTaskConsumer.onMessage(delivered);

        verify(visionAnalysisService, never()).analyze(any(), any());
        assertThat(pendingCount()).isZero(); // 실제 XACK으로 PEL에서 사라졌다
    }

    @Test
    void 취소된_세션의_메시지가_실제_XCLAIM으로_회수돼도_Vision을_호출하지_않고_실제_XACK된다() throws InterruptedException {
        // Worker가 claim(DB 저장)조차 하기 전에 죽어 메시지가 PEL에만 남은 상황을 흉내낸다
        // (AnalysisStreamRecoveryScheduler가 실제로 XCLAIM하는 것과 동일한 Redis 연산).
        Long sessionId = queuedSessionId();
        analysisTaskProducer.enqueue(new AnalysisTaskMessage(
                sessionId, List.of("https://example.com/a.jpg"), List.of("https://example.com/a.jpg")));
        MapRecord<String, String, String> delivered = readOneFromRealRedis("dead-consumer");

        productAnalyzeService.cancel(sessionId, USER_ID);

        Thread.sleep(20);
        List<MapRecord<String, String, String>> claimed = redisTemplate.<String, String>opsForStream().claim(
                streamProperties.getKey(), streamProperties.getGroup(), "recovery-consumer",
                Duration.ofMillis(10), delivered.getId()
        );
        assertThat(claimed).hasSize(1);

        analysisTaskConsumer.processReclaimed(claimed.get(0), 2L);

        verify(visionAnalysisService, never()).analyze(any(), any());
        assertThat(pendingCount()).isZero();
    }

    @Test
    void Vision_처리_중_취소한_뒤_뒤늦은_성공_응답이_와도_결과를_저장하지_않고_실제_XACK만_한다() throws InterruptedException {
        Long sessionId = queuedSessionId();
        CountDownLatch visionCalled = new CountDownLatch(1);
        CountDownLatch cancelledByTest = new CountDownLatch(1);
        VisionAnalysisResult result = new VisionAnalysisResult(
                "Nike", "Dunk Low", "Panda", 270, "설명", null, true, 0.9, false, List.of(), List.of(), List.of(), List.of()
        );
        when(visionAnalysisService.analyze(any(), any())).thenAnswer(invocation -> {
            visionCalled.countDown();
            assertThat(cancelledByTest.await(5, TimeUnit.SECONDS)).isTrue();
            return result; // 취소가 이미 커밋된 뒤에야 "늦게" 성공 응답이 도착한다.
        });

        analysisTaskProducer.enqueue(new AnalysisTaskMessage(
                sessionId, List.of("https://example.com/a.jpg"), List.of("https://example.com/a.jpg")));
        MapRecord<String, String, String> delivered = readOneFromRealRedis("consumer-1");

        Thread worker = new Thread(() -> analysisTaskConsumer.onMessage(delivered));
        worker.start();
        try {
            assertThat(visionCalled.await(5, TimeUnit.SECONDS)).isTrue();

            productAnalyzeService.cancel(sessionId, USER_ID);
            cancelledByTest.countDown();

            worker.join(5000);
            assertThat(worker.isAlive()).isFalse();

            ProductAnalysisSession reloaded = sessionRepository.findById(sessionId).orElseThrow();
            assertThat(reloaded.getVisionResultJson()).isNull();
            assertThat(pendingCount()).isZero(); // 결과는 버렸지만 메시지는 실제 XACK된다
        } finally {
            worker.interrupt();
        }
    }
}
