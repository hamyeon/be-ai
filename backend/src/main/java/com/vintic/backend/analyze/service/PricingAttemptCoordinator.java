package com.vintic.backend.analyze.service;

import com.vintic.backend.analyze.domain.ProductAnalysisSession;
import com.vintic.backend.analyze.domain.ProductAnalysisSessionRepository;
import com.vintic.backend.common.exception.AnalysisSessionNotFoundException;
import com.vintic.backend.common.exception.InvalidAnalysisStatusException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Pricing 단계의 시작(startOwned)과 종료(tryCompletePricing/tryFailPricing)를 VisionAttemptCoordinator와
// 같은 원칙으로 처리한다 - findByIdForUpdate(PESSIMISTIC_WRITE)로 세션을 짧게 잠그고 상태를 확인한 뒤
// 즉시 커밋한다. 외부 PricingService 호출(ProductPricingService)은 이 두 단계 사이, 즉 이 클래스의
// 트랜잭션 밖에서 이뤄진다 - 그래서 시작 시점에는 AWAITING_USER_CONFIRMATION이었던 세션이 Pricing
// 호출 도중 취소(cancel())될 수 있고, 종료 시점에 다시 잠가서 확인해야 그 취소를 놓치지 않는다.
//
// Vision과 달리 여러 Worker가 같은 세션을 재선점하는 구조가 없어(Pricing은 호출자 스레드가 동기로
// 수행) fencing token이 필요 없다 - 유일한 경쟁 상대는 사용자의 취소 요청(ProductAnalyzeService.cancel())
// 뿐이고, 그 경쟁은 completePricing/failPricing에 추가된 "PRICING_PROCESSING이 아니면 거절" 상태
// 가드와 같은 행 잠금만으로 충분히 막힌다.
@Service
@RequiredArgsConstructor
public class PricingAttemptCoordinator {

    private final ProductAnalysisSessionRepository sessionRepository;

    // 세션을 잠그고 소유자 확인 + startPricing(가드: AWAITING_USER_CONFIRMATION이 아니면 예외 -
    // CANCELLED/이미 PRICING_PROCESSING/COMPLETED 등을 모두 막는다) + 확정 입력값 기록까지
    // 하나의 트랜잭션으로 원자적으로 처리한다.
    @Transactional
    public void startOwned(Long sessionId, Long userId, String confirmedInputJson) {
        ProductAnalysisSession session = sessionRepository.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new AnalysisSessionNotFoundException(
                        "분석 세션을 찾을 수 없습니다. analysisId: " + sessionId
                ));

        // analysisId는 추측이 쉬운 순차 증가값이라, 타인 세션인지 여부를 노출하지 않기 위해
        // ProductPricingService.calculatePrice()/ProductAnalyzeService.getStatus()와 같은 기준으로
        // 존재하지 않을 때와 같은 404로 응답한다.
        if (!session.isOwnedBy(userId)) {
            throw new AnalysisSessionNotFoundException("분석 세션을 찾을 수 없습니다. analysisId: " + sessionId);
        }

        session.startPricing();
        session.recordConfirmedInput(confirmedInputJson);
        sessionRepository.save(session);
    }

    // 외부 Pricing 호출이 성공한 뒤 결과를 반영한다. 호출 도중 세션이 취소돼 PRICING_PROCESSING을
    // 벗어났으면 completePricing()의 상태 가드가 예외를 던지고, 여기서는 그 결과를 조용히 버리고
    // false를 반환한다 - 호출부(ProductPricingService)가 "결과가 저장되지 않았다"는 사실을 알 수
    // 있게 한다.
    @Transactional
    public boolean tryCompletePricing(Long sessionId, String pricingResultJson) {
        ProductAnalysisSession session = sessionRepository.findByIdForUpdate(sessionId).orElse(null);
        if (session == null) {
            return false;
        }
        try {
            session.completePricing(pricingResultJson);
        } catch (InvalidAnalysisStatusException e) {
            return false;
        }
        sessionRepository.save(session);
        return true;
    }

    // tryCompletePricing과 같은 이유로 가드 실패를 조용히 삼킨다 - 이미 취소된 세션을
    // PRICING_FAILED로 되돌리지 않는다.
    @Transactional
    public boolean tryFailPricing(Long sessionId, String message) {
        ProductAnalysisSession session = sessionRepository.findByIdForUpdate(sessionId).orElse(null);
        if (session == null) {
            return false;
        }
        try {
            session.failPricing(message);
        } catch (InvalidAnalysisStatusException e) {
            return false;
        }
        sessionRepository.save(session);
        return true;
    }
}
