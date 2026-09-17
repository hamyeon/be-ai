package com.vintic.backend.analyze.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.ai.vision.dto.VisionAnalysisRequest;
import com.vintic.backend.ai.vision.dto.VisionAnalysisResult;
import com.vintic.backend.ai.vision.service.VisionAnalysisService;
import com.vintic.backend.analyze.domain.ProductAnalysisSession;
import com.vintic.backend.analyze.domain.ProductAnalysisSessionRepository;
import com.vintic.backend.analyze.service.AnalysisFailureRecorder;
import com.vintic.backend.common.exception.InvalidAnalysisStatusException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.stereotype.Component;

// Redis Stream에서 분석 작업 메시지를 받아 Vision 분석을 실행하는 Consumer.
// DB 저장(완료/실패)이 성공한 뒤에만 XACK한다 - 저장 자체가 실패하면 ack하지 않고
// 미처리 메시지(Pending Entries List)로 남겨 나중에 재처리할 수 있게 한다.
// PEL에 남은 메시지는 AnalysisStreamRecovery가 일정 시간 뒤 회수해 정리한다(#106).
@Component
@RequiredArgsConstructor
@Slf4j
public class AnalysisTaskConsumer implements StreamListener<String, MapRecord<String, String, String>> {

    private static final String PAYLOAD_FIELD = "payload";
    private static final int FAILURE_MESSAGE_MAX_LENGTH = 1000;

    private final ProductAnalysisSessionRepository sessionRepository;
    private final VisionAnalysisService visionAnalysisService;
    private final AnalysisFailureRecorder failureRecorder;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;
    private final AnalysisStreamProperties properties;

    @Override
    public void onMessage(MapRecord<String, String, String> record) {
        // 여기서 예외가 새면 컨테이너 폴링 루프까지 올라간다. 구독은 끊기지 않게 해뒀지만(#106)
        // 예상 못 한 예외(세션 조회 중 DB 장애, ACK 중 Redis 장애)도 이 메시지 한 건의 실패로만 끝낸다.
        // ACK하지 않았으므로 메시지는 PEL에 남고 회수 작업이 정리한다.
        try {
            process(record);
        } catch (RuntimeException e) {
            log.error("분석 작업 처리 중 예상하지 못한 오류 - ack하지 않고 미처리로 남깁니다. recordId={}", record.getId(), e);
        }
    }

    private void process(MapRecord<String, String, String> record) {
        AnalysisTaskMessage message = parseMessage(record);
        if (message == null) {
            return; // 메시지 자체가 파싱이 안 됨 - ack 안 하고 미처리로 남김
        }

        ProductAnalysisSession session = sessionRepository.findById(message.analysisId()).orElse(null);
        if (session == null) {
            log.warn("존재하지 않는 분석 세션입니다. analysisId={}, recordId={}", message.analysisId(), record.getId());
            acknowledge(record); // 세션 자체가 없으면 재처리해도 의미가 없으니 버린다
            return;
        }

        try {
            session.startVisionProcessing(); // QUEUED가 아니면 InvalidAnalysisStatusException
            sessionRepository.save(session);
        } catch (InvalidAnalysisStatusException e) {
            log.info(
                    "이미 처리됐거나 QUEUED 상태가 아닌 중복 메시지라 건너뜁니다. analysisId={}, status={}",
                    session.getId(), session.getStatus()
            );
            acknowledge(record); // 중복 전달 - 재실행하면 안 되므로 그대로 버린다
            return;
        } catch (RuntimeException e) {
            log.error("VISION_PROCESSING 상태 저장에 실패했습니다. analysisId={}", session.getId(), e);
            return; // DB 저장 자체가 실패 - ack 안 함, 미처리로 남김
        }

        VisionAnalysisResult result;
        try {
            // 세션 ID를 같이 넘긴다. 이 분석이 부른 3단계 호출을 나중에 세션 기준으로 묶어 보려면 필요하다.
            result = visionAnalysisService.analyze(
                    new VisionAnalysisRequest(message.visionImageUrls(), session.getId()));
        } catch (RuntimeException visionError) {
            if (tryRecordVisionFailure(session.getId(), visionError)) {
                acknowledge(record);
            }
            return;
        }

        if (tryCompleteVision(session, result)) {
            acknowledge(record);
        }
    }

    private AnalysisTaskMessage parseMessage(MapRecord<String, String, String> record) {
        try {
            String payload = record.getValue().get(PAYLOAD_FIELD);
            return objectMapper.readValue(payload, AnalysisTaskMessage.class);
        } catch (Exception e) {
            log.error("분석 작업 메시지를 파싱하지 못했습니다. recordId={}", record.getId(), e);
            return null;
        }
    }

    private boolean tryCompleteVision(ProductAnalysisSession session, VisionAnalysisResult result) {
        try {
            String json = objectMapper.writeValueAsString(result);
            session.completeVision(json);
            sessionRepository.save(session);
            return true;
        } catch (Exception e) {
            log.error("Vision 분석 결과 저장에 실패했습니다. analysisId={}", session.getId(), e);
            return false;
        }
    }

    private boolean tryRecordVisionFailure(Long sessionId, RuntimeException visionError) {
        try {
            failureRecorder.recordVisionFailure(sessionId, truncate(visionError.getMessage()));
            return true;
        } catch (RuntimeException recordingError) {
            log.error("Vision 실패 상태 기록에 실패했습니다. analysisId={}", sessionId, recordingError);
            return false;
        }
    }

    private void acknowledge(MapRecord<String, String, String> record) {
        redisTemplate.opsForStream().acknowledge(properties.getKey(), properties.getGroup(), record.getId());
    }

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > FAILURE_MESSAGE_MAX_LENGTH
                ? message.substring(0, FAILURE_MESSAGE_MAX_LENGTH)
                : message;
    }
}
