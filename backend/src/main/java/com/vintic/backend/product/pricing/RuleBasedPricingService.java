package com.vintic.backend.product.pricing;

import com.vintic.backend.product.dto.CalculatePriceRequest;
import com.vintic.backend.product.dto.CalculatePriceResponse;
import com.vintic.backend.product.service.PriceCalculationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

// 기존 PriceCalculationService(규칙 기반 계산 로직)를 PricingService 인터페이스 뒤로 감싸는 얇은 어댑터.
// 계산 로직 자체는 손대지 않고, 요청/응답 DTO만 PricingRequest/PricingResult로 변환한다.
@Service
@RequiredArgsConstructor
public class RuleBasedPricingService implements PricingService {

    private final PriceCalculationService priceCalculationService;

    @Override
    public PricingResult calculate(PricingRequest request) {
        CalculatePriceRequest calculateRequest = new CalculatePriceRequest(
                request.brand(),
                request.modelName(),
                request.color(),
                request.size(),
                request.conditionGrade(),
                request.componentStatus()
        );

        CalculatePriceResponse response = priceCalculationService.calculate(calculateRequest);

        return new PricingResult(
                response.recommendedPrice(),
                response.baseMarketPrice(),
                response.kreamAveragePrice(),
                response.ebayAveragePrice(),
                response.minRecommendedPrice(),
                response.maxRecommendedPrice(),
                response.priceRange(),
                response.reason(),
                toPricingMatches(response.kreamMatches()),
                toPricingMatches(response.ebayMatches())
        );
    }

    private List<PricingResult.MatchedMarketPrice> toPricingMatches(
            List<CalculatePriceResponse.MatchedMarketPrice> matches
    ) {
        return matches.stream()
                .map(match -> new PricingResult.MatchedMarketPrice(
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
}
