package com.vintic.backend.analyze.queue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.common.exception.AnalysisQueueException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.connection.RedisStreamCommands;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

// AnalysisTaskMessage를 Redis Stream(XADD)에 적재한다. 상태와 결과의 Source of Truth는
// 여전히 ProductAnalysisSession/MySQL이고, 이 Stream은 Worker에게 작업을 전달하는 용도로만 쓴다.
@Component
@RequiredArgsConstructor
public class AnalysisTaskProducer {

    private static final String PAYLOAD_FIELD = "payload";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final AnalysisStreamProperties properties;

    public void enqueue(AnalysisTaskMessage message) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new AnalysisQueueException("분석 작업 메시지를 직렬화하는 중 오류가 발생했습니다.", e);
        }

        try {
            MapRecord<String, String, String> record = StreamRecords
                    .mapBacked(Map.of(PAYLOAD_FIELD, payload))
                    .withStreamKey(properties.getKey());
            redisTemplate.opsForStream().add(record, addOptions());
        } catch (RuntimeException e) {
            throw new AnalysisQueueException("분석 작업을 큐에 적재하는 중 오류가 발생했습니다: " + e.getMessage(), e);
        }
    }

    // ACK해도 스트림 엔트리는 지워지지 않는다. 적재할 때 길이를 대략(~) 맞춰 잘라 무한히 쌓이지 않게 한다(#106).
    // 정확한 길이(=)로 자르면 XADD마다 트리밍 비용이 들어서 근사치로 둔다.
    private RedisStreamCommands.XAddOptions addOptions() {
        if (properties.getMaxLength() <= 0) {
            return RedisStreamCommands.XAddOptions.none();
        }
        return RedisStreamCommands.XAddOptions.maxlen(properties.getMaxLength()).approximateTrimming(true);
    }
}
