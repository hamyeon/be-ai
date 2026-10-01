package com.vintic.backend.analyze.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.common.exception.AnalysisQueueException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.RedisStreamCommands;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalysisTaskProducerTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private StreamOperations<String, Object, Object> streamOperations;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AnalysisStreamProperties properties = new AnalysisStreamProperties();

    @Test
    void 메시지를_직렬화해서_XADD로_적재한다() {
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);

        AnalysisTaskProducer sut = new AnalysisTaskProducer(redisTemplate, objectMapper, properties);
        AnalysisTaskMessage message = new AnalysisTaskMessage(1L, List.of("https://example.com/a.jpg"), null);

        sut.enqueue(message);

        ArgumentCaptor<MapRecord<String, Object, Object>> recordCaptor = ArgumentCaptor.forClass(MapRecord.class);
        verify(streamOperations).add(recordCaptor.capture(), any(RedisStreamCommands.XAddOptions.class));

        MapRecord<String, Object, Object> record = recordCaptor.getValue();
        assertThat(record.getStream()).isEqualTo(properties.getKey());
        assertThat(String.valueOf(record.getValue().get("payload")))
                .contains("\"analysisId\":1")
                .contains("example.com/a.jpg");
    }

    @Test
    void 적재할_때_스트림을_설정한_길이_근처로_자른다() {
        // #106: ACK해도 엔트리가 지워지지 않아 자르지 않으면 계속 쌓인다.
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        properties.setMaxLength(500);

        new AnalysisTaskProducer(redisTemplate, objectMapper, properties)
                .enqueue(new AnalysisTaskMessage(1L, List.of("https://example.com/a.jpg"), null));

        ArgumentCaptor<RedisStreamCommands.XAddOptions> optionsCaptor =
                ArgumentCaptor.forClass(RedisStreamCommands.XAddOptions.class);
        verify(streamOperations).add(any(MapRecord.class), optionsCaptor.capture());
        assertThat(optionsCaptor.getValue().getMaxlen()).isEqualTo(500L);
        assertThat(optionsCaptor.getValue().isApproximateTrimming()).isTrue();
    }

    @Test
    void 최대_길이가_0이면_자르지_않는다() {
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        properties.setMaxLength(0);

        new AnalysisTaskProducer(redisTemplate, objectMapper, properties)
                .enqueue(new AnalysisTaskMessage(1L, List.of("https://example.com/a.jpg"), null));

        ArgumentCaptor<RedisStreamCommands.XAddOptions> optionsCaptor =
                ArgumentCaptor.forClass(RedisStreamCommands.XAddOptions.class);
        verify(streamOperations).add(any(MapRecord.class), optionsCaptor.capture());
        assertThat(optionsCaptor.getValue().hasMaxlen()).isFalse();
    }

    @Test
    void Redis_적재_자체가_실패하면_AnalysisQueueException으로_변환한다() {
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        when(streamOperations.add(any(MapRecord.class), any(RedisStreamCommands.XAddOptions.class)))
                .thenThrow(new RuntimeException("연결 실패"));

        AnalysisTaskProducer sut = new AnalysisTaskProducer(redisTemplate, objectMapper, properties);

        assertThatThrownBy(() -> sut.enqueue(new AnalysisTaskMessage(1L, List.of("https://example.com/a.jpg"), null)))
                .isInstanceOf(AnalysisQueueException.class)
                .hasCauseInstanceOf(RuntimeException.class);
    }
}
