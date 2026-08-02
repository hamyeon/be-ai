package com.vintic.backend.analyze.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.redis.DataRedisTest;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 실제 로컬 Redis(docker-compose의 redis 서비스, localhost:6379)에 대고 도는 통합 테스트.
 * @DataRedisTest는 JPA/MySQL 없이 Redis 관련 빈만 로드하므로, application-secret.yml이
 * 없어도(contextLoads()와 무관하게) Producer -> XADD -> XREADGROUP -> XACK 왕복을
 * 실제 Redis Streams API로 검증할 수 있다.
 *
 * Redis에 연결할 수 없는 환경(CI 등)에서는 @BeforeEach의 연결 확인에서 조용히 skip된다.
 */
@DataRedisTest
class AnalysisTaskProducerRedisIntegrationTest {

    @Autowired
    private StringRedisTemplate redisTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AnalysisStreamProperties properties = new AnalysisStreamProperties();
    private String testStreamKey;

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

        // 실제 개발용 스트림과 겹치지 않도록 테스트 전용 키 사용
        testStreamKey = "test:ai:analysis:requests:" + UUID.randomUUID();
        properties.setKey(testStreamKey);
        properties.setGroup("test-workers");
        properties.setConsumerPrefix("test-worker");
    }

    @AfterEach
    void tearDown() {
        if (testStreamKey != null) {
            redisTemplate.delete(testStreamKey);
        }
    }

    @Test
    void Producer가_적재한_메시지를_ConsumerGroup으로_읽고_XACK하면_미처리_목록에서_사라진다() {
        AnalysisTaskProducer producer = new AnalysisTaskProducer(redisTemplate, objectMapper, properties);
        AnalysisTaskMessage message = new AnalysisTaskMessage(42L, List.of("https://example.com/a.jpg", "https://example.com/b.jpg"));

        producer.enqueue(message);

        // RedisStreamConsumerConfig.createConsumerGroupIfAbsent()와 동일한 방식으로 그룹 생성
        redisTemplate.opsForStream().createGroup(testStreamKey, ReadOffset.from("0"), properties.getGroup());

        String consumerName = properties.getConsumerPrefix() + "-1";
        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().read(
                Consumer.from(properties.getGroup(), consumerName),
                StreamReadOptions.empty().count(10),
                StreamOffset.create(testStreamKey, ReadOffset.lastConsumed())
        );

        assertThat(records).hasSize(1);
        MapRecord<String, Object, Object> record = records.get(0);
        String payload = String.valueOf(record.getValue().get("payload"));
        assertThat(payload).contains("\"analysisId\":42").contains("example.com/a.jpg");

        // ack 전에는 pending(미처리)으로 남아있어야 한다
        PendingMessages pendingBeforeAck = redisTemplate.opsForStream()
                .pending(testStreamKey, properties.getGroup(), Range.unbounded(), 10);
        assertThat(pendingBeforeAck.size()).isEqualTo(1);

        Long acked = redisTemplate.opsForStream().acknowledge(testStreamKey, properties.getGroup(), record.getId());
        assertThat(acked).isEqualTo(1L);

        PendingMessages pendingAfterAck = redisTemplate.opsForStream()
                .pending(testStreamKey, properties.getGroup(), Range.unbounded(), 10);
        assertThat(pendingAfterAck.size()).isEqualTo(0);
    }
}
