package com.vintic.backend.analyze.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.ai.vision.dto.ConditionGrade;
import com.vintic.backend.ai.vision.dto.VisionAnalysisRequest;
import com.vintic.backend.ai.vision.dto.VisionAnalysisResult;
import com.vintic.backend.ai.vision.service.VisionAnalysisService;
import com.vintic.backend.analyze.domain.AnalysisStatus;
import com.vintic.backend.analyze.domain.ProductAnalysisSession;
import com.vintic.backend.analyze.domain.ProductAnalysisSessionRepository;
import com.vintic.backend.analyze.service.AnalysisFailureRecorder;
import com.vintic.backend.common.exception.AiApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalysisTaskConsumerTest {

    @Mock
    private ProductAnalysisSessionRepository sessionRepository;

    @Mock
    private VisionAnalysisService visionAnalysisService;

    @Mock
    private AnalysisFailureRecorder failureRecorder;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private StreamOperations<String, Object, Object> streamOperations;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AnalysisStreamProperties properties = new AnalysisStreamProperties();

    private AnalysisTaskConsumer newConsumer() {
        return new AnalysisTaskConsumer(
                sessionRepository, visionAnalysisService, failureRecorder, objectMapper, redisTemplate, properties
        );
    }

    private MapRecord<String, String, String> recordFor(Long analysisId, List<String> imageUrls) {
        try {
            String payload = objectMapper.writeValueAsString(new AnalysisTaskMessage(analysisId, imageUrls));
            return StreamRecords.<String, String, String>mapBacked(Map.of("payload", payload))
                    .withStreamKey(properties.getKey())
                    .withId(RecordId.of("1-0"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private ProductAnalysisSession queuedSession(List<String> imageUrls) {
        ProductAnalysisSession session = ProductAnalysisSession.create();
        session.markImageUploaded(imageUrls);
        session.markQueued();
        return session;
    }

    @Test
    void 정상_처리되면_Vision_결과를_저장하고_ACK한다() {
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        List<String> imageUrls = List.of("https://example.com/a.jpg");
        ProductAnalysisSession session = queuedSession(imageUrls);
        when(sessionRepository.findById(1L)).thenReturn(Optional.of(session));
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        VisionAnalysisResult result = new VisionAnalysisResult(
                "Nike", "Dunk Low", "Panda", 270, "설명", ConditionGrade.B,
                true, 0.9, false, List.of(), List.of()
        );
        when(visionAnalysisService.analyze(new VisionAnalysisRequest(imageUrls))).thenReturn(result);

        newConsumer().onMessage(recordFor(1L, imageUrls));

        assertThat(session.getStatus()).isEqualTo(AnalysisStatus.AWAITING_USER_CONFIRMATION);
        assertThat(session.getVisionResultJson()).contains("\"brand\":\"Nike\"");
        verify(streamOperations).acknowledge(eq(properties.getKey()), eq(properties.getGroup()), eq(RecordId.of("1-0")));
    }

    @Test
    void Vision_호출이_실패하면_실패_기록_후_ACK한다() {
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        List<String> imageUrls = List.of("https://example.com/a.jpg");
        ProductAnalysisSession session = queuedSession(imageUrls);
        when(sessionRepository.findById(1L)).thenReturn(Optional.of(session));
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(visionAnalysisService.analyze(any())).thenThrow(new AiApiException("OpenAI 오류"));

        newConsumer().onMessage(recordFor(1L, imageUrls));

        // session은 실제 저장을 안 거쳐서 getId()가 null이라 any()로 검증 (메시지 전달 여부만 확인)
        verify(failureRecorder).recordVisionFailure(any(), eq("OpenAI 오류"));
        verify(streamOperations).acknowledge(eq(properties.getKey()), eq(properties.getGroup()), eq(RecordId.of("1-0")));
    }

    @Test
    void Vision_실패_기록_저장_자체가_실패하면_ACK하지_않는다() {
        List<String> imageUrls = List.of("https://example.com/a.jpg");
        ProductAnalysisSession session = queuedSession(imageUrls);
        when(sessionRepository.findById(1L)).thenReturn(Optional.of(session));
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(visionAnalysisService.analyze(any())).thenThrow(new AiApiException("OpenAI 오류"));
        doThrow(new RuntimeException("DB 오류")).when(failureRecorder).recordVisionFailure(anyLong(), anyString());

        newConsumer().onMessage(recordFor(1L, imageUrls));

        verify(redisTemplate, never()).opsForStream();
    }

    @Test
    void 존재하지_않는_세션이면_Vision을_호출하지_않고_ACK한다() {
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        when(sessionRepository.findById(1L)).thenReturn(Optional.empty());

        newConsumer().onMessage(recordFor(1L, List.of("https://example.com/a.jpg")));

        verify(visionAnalysisService, never()).analyze(any());
        verify(streamOperations).acknowledge(eq(properties.getKey()), eq(properties.getGroup()), eq(RecordId.of("1-0")));
    }

    @Test
    void QUEUED_상태가_아닌_중복_메시지는_Vision을_호출하지_않고_ACK한다() {
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        List<String> imageUrls = List.of("https://example.com/a.jpg");
        ProductAnalysisSession session = queuedSession(imageUrls);
        session.startVisionProcessing();
        session.completeVision("{}"); // 이미 AWAITING_USER_CONFIRMATION까지 진행된 상태 = 중복 전달 상황
        when(sessionRepository.findById(1L)).thenReturn(Optional.of(session));

        newConsumer().onMessage(recordFor(1L, imageUrls));

        verify(visionAnalysisService, never()).analyze(any());
        verify(streamOperations).acknowledge(eq(properties.getKey()), eq(properties.getGroup()), eq(RecordId.of("1-0")));
    }

    @Test
    void VISION_PROCESSING_상태_저장이_실패하면_ACK하지_않는다() {
        List<String> imageUrls = List.of("https://example.com/a.jpg");
        ProductAnalysisSession session = queuedSession(imageUrls);
        when(sessionRepository.findById(1L)).thenReturn(Optional.of(session));
        when(sessionRepository.save(any())).thenThrow(new RuntimeException("DB 연결 실패"));

        newConsumer().onMessage(recordFor(1L, imageUrls));

        verify(visionAnalysisService, never()).analyze(any());
        verify(redisTemplate, never()).opsForStream();
    }

    @Test
    void Vision_결과_저장이_실패하면_ACK하지_않는다() {
        List<String> imageUrls = List.of("https://example.com/a.jpg");
        ProductAnalysisSession session = queuedSession(imageUrls);
        when(sessionRepository.findById(1L)).thenReturn(Optional.of(session));
        when(sessionRepository.save(any()))
                .thenAnswer(inv -> inv.getArgument(0)) // VISION_PROCESSING 저장은 성공
                .thenThrow(new RuntimeException("DB 연결 실패")); // completeVision 저장은 실패

        VisionAnalysisResult result = new VisionAnalysisResult(
                "Nike", "Dunk Low", "Panda", 270, "설명", ConditionGrade.B,
                true, 0.9, false, List.of(), List.of()
        );
        when(visionAnalysisService.analyze(any())).thenReturn(result);

        newConsumer().onMessage(recordFor(1L, imageUrls));

        verify(redisTemplate, never()).opsForStream();
    }

    @Test
    void 메시지_파싱이_안되면_세션_조회조차_안_하고_ACK하지_않는다() {
        MapRecord<String, String, String> malformed = StreamRecords.<String, String, String>mapBacked(Map.of("payload", "이건 JSON이 아닙니다"))
                .withStreamKey(properties.getKey())
                .withId(RecordId.of("1-0"));

        newConsumer().onMessage(malformed);

        verify(sessionRepository, never()).findById(any());
        verify(redisTemplate, never()).opsForStream();
    }
}
