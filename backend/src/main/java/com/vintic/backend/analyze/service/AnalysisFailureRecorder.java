package com.vintic.backend.analyze.service;

import com.vintic.backend.analyze.domain.AnalysisStatus;
import com.vintic.backend.analyze.domain.ProductAnalysisSession;
import com.vintic.backend.analyze.domain.ProductAnalysisSessionRepository;
import com.vintic.backend.analyze.domain.VisionAttemptOutcome;
import com.vintic.backend.common.exception.AnalysisSessionNotFoundException;
import com.vintic.backend.common.exception.InvalidAnalysisStatusException;
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
    public void recordQueueingFailure(Long sessionId, String message) {
        ProductAnalysisSession session = findSession(sessionId);
        session.failQueueing(message);
        sessionRepository.save(session);
    }

    // VisionAttemptCoordinator와 같은 원칙(findByIdForUpdate + token 가드)을 쓰되, 이 메서드만
    // REQUIRES_NEW로 유지한다 - 실패 기록은 호출부 트랜잭션이 나중에 롤백되더라도 항상 별도로
    // 커밋되어야 하기 때문이다(클래스 상단 주석 참고). token이 현재 소유한 시도와 다르면(다른
    // Worker가 이미 재선점함) 실패를 기록하지 않고 OWNERSHIP_LOST를 반환한다 - 호출부는 이 경우
    // ACK하면 안 된다.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public VisionAttemptOutcome recordVisionFailure(Long sessionId, String token, String message) {
        ProductAnalysisSession session = sessionRepository.findByIdForUpdate(sessionId).orElse(null);
        if (session == null) {
            return VisionAttemptOutcome.ALREADY_FINALIZED;
        }
        AnalysisStatus statusBeforeAttempt = session.getStatus();
        try {
            session.failVision(token, message);
        } catch (InvalidAnalysisStatusException e) {
            return VisionAttemptOutcome.fromGuardFailure(statusBeforeAttempt);
        }
        sessionRepository.save(session);
        return VisionAttemptOutcome.COMMITTED;
    }

    private ProductAnalysisSession findSession(Long sessionId) {
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> new AnalysisSessionNotFoundException(
                        "분석 세션을 찾을 수 없습니다. analysisId: " + sessionId
                ));
    }
}
