package com.vintic.backend.analyze.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.analyze.domain.ProductAnalysisSession;
import com.vintic.backend.analyze.domain.ProductAnalysisSessionRepository;
import com.vintic.backend.common.exception.AiApiException;
import com.vintic.backend.common.exception.AnalysisSessionNotFoundException;
import com.vintic.backend.product.dto.CalculatePriceRequest;
import com.vintic.backend.product.dto.CalculatePriceResponse;
import com.vintic.backend.product.pricing.PricingRequest;
import com.vintic.backend.product.pricing.PricingResult;
import com.vintic.backend.product.pricing.PricingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

// analysisId로 세션을 찾아 확정 입력값을 기록하고, PricingService를 호출해 상태를 갱신하는 오케스트레이터.
// Controller는 요청 수신/응답 반환만 담당하고, 세션 조회/상태 전환/실패 기록 책임은 여기로 모은다.
@Service
@RequiredArgsConstructor
@Slf4j
public class ProductPricingService {

    private static final int FAILURE_MESSAGE_MAX_LENGTH = 1000;

    private final PricingService pricingService;
    private final ProductAnalysisSessionRepository sessionRepository;
    private final AnalysisFailureRecorder failureRecorder;
    private final ObjectMapper objectMapper;

    public CalculatePriceResponse calculatePrice(CalculatePriceRequest request) {
        ProductAnalysisSession session = sessionRepository.findById(request.analysisId())
                .orElseThrow(() -> new AnalysisSessionNotFoundException(
                        "분석 세션을 찾을 수 없습니다. analysisId: " + request.analysisId()
                ));

        PricingRequest pricingRequest = new PricingRequest(
                request.brand(),
                request.modelName(),
                request.color(),
                request.size(),
                request.conditionGrade(),
                request.componentStatus()
        );

        // 직렬화가 실패하면(비정상적인 경우) 여기서 예외가 던져지고, 세션 상태는 아직
        // AWAITING_USER_CONFIRMATION 그대로 유지된다 - PRICING_PROCESSING으로는 절대 안 넘어간다.
        String confirmedInputJson = toJson(pricingRequest);

        session.startPricing();
        session.recordConfirmedInput(confirmedInputJson);
        sessionRepository.save(session);

        PricingResult result;
        try {
            result = pricingService.calculate(pricingRequest);
        } catch (RuntimeException e) {
            recordFailureSafely(session.getId(), truncate(e.getMessage()));
            throw e;
        }

        session.completePricing(toJson(result));
        sessionRepository.save(session);

        return new CalculatePriceResponse(
                result.recommendedPrice(),
                result.baseMarketPrice(),
                result.kreamAveragePrice(),
                result.ebayAveragePrice(),
                result.minRecommendedPrice(),
                result.maxRecommendedPrice(),
                result.priceRange(),
                result.reason(),
                toCalculateMatches(result.kreamMatches()),
                toCalculateMatches(result.ebayMatches())
        );
    }

    // 실패 상태 기록 중 추가 오류가 나도, 원래 발생한 Pricing 예외가 덮어써지면 안 되므로
    // 여기서 삼키고 로그만 남긴다. 호출부는 항상 원래 예외를 다시 던진다.
    private void recordFailureSafely(Long sessionId, String message) {
        try {
            failureRecorder.recordPricingFailure(sessionId, message);
        } catch (RuntimeException recordingError) {
            log.error("Pricing 실패 상태 기록 중 추가 오류가 발생했습니다. sessionId={}", sessionId, recordingError);
        }
    }

    private List<CalculatePriceResponse.MatchedMarketPrice> toCalculateMatches(
            List<PricingResult.MatchedMarketPrice> matches
    ) {
        return matches.stream()
                .map(match -> new CalculatePriceResponse.MatchedMarketPrice(
                        match.source(),
                        match.brand(),
                        match.modelName(),
                        match.color(),
                        match.size(),
                        match.conditionGrade(),
                        match.componentStatus(),
                        match.price(),
                        match.url()
                ))
                .toList();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new AiApiException("가격 계산 결과를 저장하는 중 오류가 발생했습니다.");
        }
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
