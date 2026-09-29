package com.vintic.backend.analyze.service;

import com.vintic.backend.analyze.domain.AnalysisStatus;
import com.vintic.backend.analyze.domain.ProductAnalysisSession;
import com.vintic.backend.analyze.domain.ProductAnalysisSessionRepository;
import com.vintic.backend.analyze.queue.VisionFailureEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

// VISION_FAILED 세션을 실패 전용 Stream에 발행했는지 DB에서 확인/기록한다. Redis 호출(XADD)은
// 이 클래스가 하지 않는다 - 짧은 DB 트랜잭션 안에 외부 I/O를 두지 않는다는 원칙(VisionAttemptCoordinator
// 와 동일)을 지키기 위해, 실제 발행은 호출부(AnalysisTaskConsumer)가 이 클래스의 트랜잭션 밖에서
// VisionFailureStreamProducer로 수행한다.
@Service
@RequiredArgsConstructor
public class VisionFailureStreamRecorder {

    private final ProductAnalysisSessionRepository sessionRepository;

    // 발행이 필요한 세션이면 이벤트를 반환하고, VISION_FAILED가 아니거나 이미 발행됐다면
    // empty를 반환한다(호출부는 이 경우 그대로 ACK해도 안전하다).
    public Optional<VisionFailureEvent> pendingFailureEvent(Long sessionId) {
        ProductAnalysisSession session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null
                || session.getStatus() != AnalysisStatus.VISION_FAILED
                || session.isVisionFailureStreamPublished()) {
            return Optional.empty();
        }
        return Optional.of(new VisionFailureEvent(
                sessionId,
                session.getFailureStage() == null ? null : session.getFailureStage().name(),
                session.getFailureMessage(),
                System.currentTimeMillis()
        ));
    }

    @Transactional
    public void markPublished(Long sessionId) {
        ProductAnalysisSession session = sessionRepository.findById(sessionId).orElseThrow();
        session.markVisionFailureStreamPublished();
        sessionRepository.save(session);
    }
}
