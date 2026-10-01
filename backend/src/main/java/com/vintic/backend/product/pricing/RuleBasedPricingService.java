package com.vintic.backend.product.pricing;

import com.vintic.backend.config.CacheConfig;
import com.vintic.backend.product.dto.CalculatePriceRequest;
import com.vintic.backend.product.dto.CalculatePriceResponse;
import com.vintic.backend.product.service.PriceCalculationService;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

// 기존 PriceCalculationService(규칙 기반 계산 로직)를 PricingService 인터페이스 뒤로 감싸는 얇은 어댑터.
// 계산 로직 자체는 손대지 않고, 요청/응답 DTO만 PricingRequest/PricingResult로 변환한다.
@Service
@RequiredArgsConstructor
public class RuleBasedPricingService implements PricingService {

    private final PriceCalculationService priceCalculationService;

    // 같은 입력(브랜드/모델/색상/사이즈/등급/구성품)이면 결과가 같으므로 캐싱한다.
    // 시세 원천이 CSV라 지금은 계산이 싸지만, 캐시 계층을 여기 두면 나중에 계산이
    // 실시간 시세 조회로 바뀌어도 호출부는 그대로다.
    // 주의: analysisId가 섞인 CalculatePriceRequest가 아니라 PricingRequest 층에 캐시를 둔다 -
    // analysisId는 사용자마다 달라서 그 층에 두면 같은 신발도 캐시를 못 탄다.
    @Override
    @Cacheable(cacheNames = CacheConfig.PRICING_CACHE, key = "#request.cacheKey()")
    public PricingResult calculate(PricingRequest request) {
        CalculatePriceRequest calculateRequest = new CalculatePriceRequest(
                null,
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
