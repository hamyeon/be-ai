package com.vintic.backend.analyze.service;

import com.vintic.backend.analyze.domain.ProductAnalysisSession;
import com.vintic.backend.analyze.domain.ProductAnalysisSessionRepository;
import com.vintic.backend.common.exception.AnalysisSessionNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// 실패 상태 기록은 호출부의 트랜잭션이 나중에 롤백되더라도 항상 별도로 커밋되어야 하므로
// REQUIRES_NEW로 분리한다. 예: 오케스트레이터 메서드에 나중에 @Transactional이 붙어도
// 이 메서드가 남긴 실패 기록은 그 트랜잭션과 함께 롤백되지 않는다.
// (REQUIRES_NEW 프록시가 걸리려면 별도 빈으로 호출돼야 하므로, 실패를 기록하는 서비스와
//  분리된 클래스로 둔다 - 같은 클래스 내 self-invocation은 프록시를 우회한다.)
@Service
@RequiredArgsConstructor
public class AnalysisFailureRecorder {

    private final ProductAnalysisSessionRepository sessionRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordImageUploadFailure(Long sessionId, String message) {
        ProductAnalysisSession session = findSession(sessionId);
        session.failImageUpload(message);
        sessionRepository.save(session);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordVisionFailure(Long sessionId, String message) {
        ProductAnalysisSession session = findSession(sessionId);
        session.failVision(message);
        sessionRepository.save(session);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordPricingFailure(Long sessionId, String message) {
        ProductAnalysisSession session = findSession(sessionId);
        session.failPricing(message);
        sessionRepository.save(session);
    }

    private ProductAnalysisSession findSession(Long sessionId) {
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> new AnalysisSessionNotFoundException(
                        "분석 세션을 찾을 수 없습니다. analysisId: " + sessionId
                ));
    }
}
