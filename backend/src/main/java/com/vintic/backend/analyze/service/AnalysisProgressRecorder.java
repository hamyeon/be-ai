package com.vintic.backend.analyze.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.ai.vision.dto.VisionProgress;
import com.vintic.backend.analyze.domain.ProductAnalysisSession;
import com.vintic.backend.analyze.domain.ProductAnalysisSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// Vision 단계가 끝날 때마다 잠정 결과를 세션에 저장한다(#106). 상태 조회 API가 폴링 중에 이 값을 내려준다.
//
// 완료·실패·회수(VisionAttemptCoordinator/AnalysisFailureRecorder)와 같은 행 잠금(findByIdForUpdate)으로 읽는다.
// 세션 전체를 저장하므로, 잠그지 않으면 전체 시간 상한(overall-timeout)을 넘겨 실패로 정리된 뒤 늦게 도착한
// 진행 기록이 VISION_FAILED를 VISION_PROCESSING으로 되돌릴 수 있다. 잠근 뒤 상태를 보면 그런 기록은 버려진다.
// 실패 기록과 같은 이유로 REQUIRES_NEW로 분리한다.
@Service
@RequiredArgsConstructor
@Slf4j
public class AnalysisProgressRecorder {

    private final ProductAnalysisSessionRepository sessionRepository;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordVisionProgress(Long sessionId, VisionProgress progress) {
        ProductAnalysisSession session = sessionRepository.findByIdForUpdate(sessionId).orElse(null);
        if (session == null) {
            return;
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(progress);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Vision 진행 상황을 직렬화하지 못했습니다.", e);
        }
        if (session.recordVisionProgress(json)) {
            sessionRepository.save(session);
        } else {
            log.debug("분석 중이 아닌 세션이라 진행 기록을 버립니다. analysisId={}, status={}", sessionId, session.getStatus());
        }
    }
}
