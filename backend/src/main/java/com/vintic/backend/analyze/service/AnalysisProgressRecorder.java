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
// Consumer가 들고 있는 세션 객체에 쓰지 않고 매번 새로 읽어 저장한다. Consumer는 분석이 끝난 뒤 자기 객체를
// 저장하는데(completeVision), 거기서 잠정 결과를 비우므로 두 쓰기가 섞여도 최종 상태는 같다.
// 실패 기록(AnalysisFailureRecorder)과 같은 이유로 REQUIRES_NEW로 분리한다.
@Service
@RequiredArgsConstructor
@Slf4j
public class AnalysisProgressRecorder {

    private final ProductAnalysisSessionRepository sessionRepository;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordVisionProgress(Long sessionId, VisionProgress progress) {
        ProductAnalysisSession session = sessionRepository.findById(sessionId).orElse(null);
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
