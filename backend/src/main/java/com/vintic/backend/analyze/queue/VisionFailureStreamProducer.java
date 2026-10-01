package com.vintic.backend.analyze.queue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.common.exception.AnalysisQueueException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

// 최종 실패(VISION_FAILED)를 실패 전용 Stream(XADD)에 남긴다. AnalysisTaskProducer와 동일한
// 원칙 - Source of Truth는 여전히 MySQL(ProductAnalysisSession)이고, 이 Stream은 실패를
// 관측/후속 조치(알림, 재처리 트리거 등)로 이어주는 전달 통로일 뿐이다.
@Component
@RequiredArgsConstructor
public class VisionFailureStreamProducer {

    private static final String PAYLOAD_FIELD = "payload";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final AnalysisStreamProperties properties;

    public void publish(VisionFailureEvent event) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new AnalysisQueueException("실패 이벤트를 직렬화하는 중 오류가 발생했습니다.", e);
        }
        try {
            redisTemplate.opsForStream().add(
                    StreamRecords.mapBacked(Map.of(PAYLOAD_FIELD, payload)).withStreamKey(properties.getFailureKey())
            );
        } catch (RuntimeException e) {
            throw new AnalysisQueueException("실패 이벤트를 Stream에 발행하는 중 오류가 발생했습니다: " + e.getMessage(), e);
        }
    }
}
