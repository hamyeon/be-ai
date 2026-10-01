package com.vintic.backend.analyze.service;

import com.vintic.backend.analyze.domain.AnalysisStatus;
import com.vintic.backend.analyze.domain.ProductAnalysisSession;
import com.vintic.backend.analyze.domain.ProductAnalysisSessionRepository;
import com.vintic.backend.analyze.domain.VisionAttemptOutcome;
import com.vintic.backend.analyze.domain.VisionFailureAttemptResult;
import com.vintic.backend.common.exception.InvalidAnalysisStatusException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.Consumer;

// Vision 처리 시도의 소유권 이전(claim/reclaim)과 성공 확정(complete)을 짧은 트랜잭션 단위로
// 원자적으로 처리한다. 각 메서드는 findByIdForUpdate(PESSIMISTIC_WRITE)로 세션을 짧게 잠그고
// 상태/token을 확인한 뒤 즉시 커밋한다 - Vision API 호출처럼 오래 걸리는 외부 호출은 이
// 트랜잭션 밖(AnalysisTaskConsumer)에서 이뤄지므로, 이 서비스의 메서드 호출 사이에는 항상
// Vision 호출이 끼어 있을 수 있다는 전제로 동작한다.
//
// 실패(fail) 기록은 이미 존재하는 AnalysisFailureRecorder가 같은 원칙(REQUIRES_NEW로 외부
// 트랜잭션 롤백과 분리)으로 처리하므로 여기서 중복하지 않는다.
@Service
@RequiredArgsConstructor
public class VisionAttemptCoordinator {

    private final ProductAnalysisSessionRepository sessionRepository;

    @Transactional
    public VisionAttemptOutcome claim(Long sessionId, String token) {
        return attempt(sessionId, session -> session.claimVisionProcessing(token));
    }

    @Transactional
    public VisionAttemptOutcome reclaim(Long sessionId, String token) {
        return attempt(sessionId, session -> session.reclaimVisionProcessing(token));
    }

    @Transactional
    public VisionAttemptOutcome complete(Long sessionId, String token, String visionResultJson) {
        return attempt(sessionId, session -> session.completeVision(token, visionResultJson));
    }

    // Redis 배달 횟수가 아니라 "진짜로 Vision을 시도했다가 재시도 가치가 있는 이유로 실패한
    // 횟수"를 DB에 남긴다(ProductAnalysisSession.visionFailureAttemptCount 참고). attempt()와
    // 달리 증가 후의 카운트 값도 호출부(AnalysisTaskConsumer)에 돌려줘야 해서 별도로 둔다.
    @Transactional
    public VisionFailureAttemptResult incrementFailureAttempt(Long sessionId, String token) {
        ProductAnalysisSession session = sessionRepository.findByIdForUpdate(sessionId).orElse(null);
        if (session == null) {
            return new VisionFailureAttemptResult(VisionAttemptOutcome.ALREADY_FINALIZED, 0);
        }
        AnalysisStatus statusBeforeAttempt = session.getStatus();
        try {
            session.incrementVisionFailureAttemptCount(token);
        } catch (InvalidAnalysisStatusException e) {
            return new VisionFailureAttemptResult(VisionAttemptOutcome.fromGuardFailure(statusBeforeAttempt), 0);
        }
        sessionRepository.save(session);
        return new VisionFailureAttemptResult(VisionAttemptOutcome.COMMITTED, session.getVisionFailureAttemptCount());
    }

    private VisionAttemptOutcome attempt(Long sessionId, Consumer<ProductAnalysisSession> mutation) {
        ProductAnalysisSession session = sessionRepository.findByIdForUpdate(sessionId).orElse(null);
        if (session == null) {
            return VisionAttemptOutcome.ALREADY_FINALIZED; // 세션 자체가 없음 - 재처리 의미 없음
        }
        AnalysisStatus statusBeforeAttempt = session.getStatus();
        try {
            mutation.accept(session);
        } catch (InvalidAnalysisStatusException e) {
            return VisionAttemptOutcome.fromGuardFailure(statusBeforeAttempt);
        }
        sessionRepository.save(session);
        return VisionAttemptOutcome.COMMITTED;
    }
}
