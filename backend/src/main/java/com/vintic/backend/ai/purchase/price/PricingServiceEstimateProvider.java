package com.vintic.backend.ai.purchase.price;

import com.vintic.backend.ai.purchase.model.ModelAliases;
import com.vintic.backend.product.pricing.PricingRequest;
import com.vintic.backend.product.pricing.PricingResult;
import com.vintic.backend.product.pricing.PricingService;
import com.vintic.backend.product.service.UsedMarketPriceProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

// 기존 PricingService(#86/#93 실거래 시세 계산)를 그대로 불러 PriceEstimate로 옮긴다. 새 계산식은 없다.
//
// 한 가지 보정: 매물의 brand/model을 별칭 표로 접어 카탈로그 표기로 바꿔 넘긴다. Product.model은
// Vision 초안 기반 자유 텍스트("덩크 로우", "990v6")라 시세 CSV의 표기와 문자로 안 맞는 경우가
// 있는데, 별칭 표가 아는 모델이면 카탈로그 표기("Dunk Low")로 물어 매칭을 살린다.
// 별칭 표가 모르는 모델은 원문 그대로 넘긴다 - 기존 매칭(포함 검사)이 잡을 수도 있다.
@Component
@RequiredArgsConstructor
@Slf4j
public class PricingServiceEstimateProvider implements PriceEstimateProvider {

    private final PricingService pricingService;
    private final UsedMarketPriceProvider usedMarketPriceProvider;
    private final ModelAliases modelAliases;
    private final Clock clock;

    @Override
    public Optional<PriceEstimate> estimate(PriceEstimateQuery query) {
        String brand = query.brand();
        String model = query.model();
        Optional<ModelAliases.Match> catalog = modelAliases.find(join(brand, model));
        if (catalog.isPresent()) {
            brand = catalog.get().model().brand();
            model = catalog.get().model().modelDisplay();
        }

        PricingResult result = pricingService.calculate(new PricingRequest(
                brand, model, query.colorway(), query.sizeKr(), query.conditionGrade(), query.componentStatus()));

        // "시세 정보 없음"은 recommendedPrice 0으로 온다(PriceCalculationService의 3순위 응답).
        if (result.recommendedPrice() <= 0) {
            log.info("Agent 시세 없음: brand={}, model={}", brand, model);
            return Optional.empty();
        }

        // 1순위(실거래) 경로는 KREAM 필드를 0으로 비워 돌려준다. 그래서 kreamAveragePrice > 0이면 2순위다.
        PriceEstimate.Source source = result.kreamAveragePrice() > 0
                ? PriceEstimate.Source.KREAM
                : PriceEstimate.Source.USED_MARKET;
        int sampleCount = source == PriceEstimate.Source.USED_MARKET
                ? usedMarketPriceProvider.find(brand, model).map(UsedMarketPriceProvider.UsedMarketPrice::listingCount).orElse(0)
                : 0;

        // 2순위(KREAM)는 IQR이 없어 min/max가 0일 수 있다. 그때는 추천가로 채워 "범위 없음"을 드러낸다.
        int lower = result.minRecommendedPrice() > 0 ? result.minRecommendedPrice() : result.recommendedPrice();
        int upper = result.maxRecommendedPrice() > 0 ? result.maxRecommendedPrice() : result.recommendedPrice();

        return Optional.of(new PriceEstimate(
                result.recommendedPrice(), lower, upper, source, sampleCount, result.reason(), LocalDateTime.now(clock)));
    }

    private static String join(String brand, String model) {
        if (brand == null || brand.isBlank()) {
            return model == null ? "" : model;
        }
        return model == null ? brand : brand + " " + model;
    }
}
