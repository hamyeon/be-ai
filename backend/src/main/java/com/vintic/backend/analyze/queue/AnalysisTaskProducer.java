package com.vintic.backend.analyze.queue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.common.exception.AnalysisQueueException;
import lombok.RequiredArgsConstructor;
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
            redisTemplate.opsForStream().add(record);
        } catch (RuntimeException e) {
            throw new AnalysisQueueException("분석 작업을 큐에 적재하는 중 오류가 발생했습니다: " + e.getMessage(), e);
        }
    }
}
