package com.vintic.backend.product.service;

import com.vintic.backend.product.dto.CalculatePriceRequest;
import com.vintic.backend.product.dto.CalculatePriceResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PriceCalculationServiceTest {

    @Mock
    private MarketPriceDataLoader marketPriceDataLoader;

    @Mock
    private UsedMarketPriceProvider usedMarketPriceProvider;

    // 상태 계수는 실측 CSV까지 실제로 읽어야 비율(#61 값)이 의미를 갖는다
    private final ConditionRateProvider conditionRateProvider = new ConditionRateProvider();

    private PriceCalculationService newService() {
        return new PriceCalculationService(
                marketPriceDataLoader, conditionRateProvider, usedMarketPriceProvider);
    }

    private CalculatePriceRequest request(String brand, String model, String grade) {
        return new CalculatePriceRequest(1L, brand, model, "Panda", 270, grade, "FULL");
    }

    private UsedMarketPriceProvider.UsedMarketPrice dunkLowMarket() {
        return new UsedMarketPriceProvider.UsedMarketPrice(
                "Nike", "nike", "dunklow", "Dunk Low", 116, 40_000, 25_000, 81_000);
    }

    @Test
    void 중고_시세가_있으면_그것을_1순위로_쓴다() {
        when(usedMarketPriceProvider.find("Nike", "Dunk Low"))
                .thenReturn(Optional.of(dunkLowMarket()));

        CalculatePriceResponse response = newService().calculate(request("Nike", "Dunk Low", "UNKNOWN"));

        // 일반 중고(UNKNOWN)는 비율 1.0이라 중앙값이 그대로 기준이 된다
        assertThat(response.recommendedPrice()).isEqualTo(40_000);
        assertThat(response.baseMarketPrice()).isEqualTo(40_000);
        // 권장 범위는 ±5%가 아니라 실거래 IQR이다
        assertThat(response.minRecommendedPrice()).isEqualTo(25_000);
        assertThat(response.maxRecommendedPrice()).isEqualTo(81_000);
        assertThat(response.reason()).contains("중고 매물 116건", "당근마켓");
        // 이 경로에서는 KREAM/eBay CSV를 읽을 필요조차 없다
        verify(marketPriceDataLoader, never()).loadKreamRows();
    }

    @Test
    void 상태가_좋으면_일반_중고_대비_실측_비율만큼_오른다() {
        when(usedMarketPriceProvider.find("Nike", "Dunk Low"))
                .thenReturn(Optional.of(dunkLowMarket()));

        CalculatePriceResponse ds = newService().calculate(request("Nike", "Dunk Low", "DS"));
        CalculatePriceResponse unknown = newService().calculate(request("Nike", "Dunk Low", "UNKNOWN"));

        // DS/UNKNOWN 실측 계수 비율(0.778/0.415 ≈ 1.87)로만 움직인다 - 지어낸 숫자가 없다
        double ratio = (double) ds.recommendedPrice() / unknown.recommendedPrice();
        assertThat(ratio).isCloseTo(0.778 / 0.415, within(0.05));
    }

    @Test
    void 상태가_나쁘면_중앙값_아래로_내려간다() {
        when(usedMarketPriceProvider.find("Nike", "Dunk Low"))
                .thenReturn(Optional.of(dunkLowMarket()));

        CalculatePriceResponse response = newService().calculate(request("Nike", "Dunk Low", "C"));

        assertThat(response.recommendedPrice()).isLessThan(40_000);
    }

    @Test
    void 중고_시세가_없으면_기존_방식으로_폴백한다() {
        when(usedMarketPriceProvider.find(anyString(), anyString())).thenReturn(Optional.empty());
        when(marketPriceDataLoader.loadKreamRows()).thenReturn(List.of());
        when(marketPriceDataLoader.loadEbayRows()).thenReturn(List.of());

        CalculatePriceResponse response = newService().calculate(request("Nike", "없는모델", "A"));

        // 중고도 KREAM/eBay도 없으면 기존과 같은 "시세 정보 없음"
        assertThat(response.recommendedPrice()).isZero();
        assertThat(response.priceRange()).isEqualTo("시세 정보 없음");
        verify(marketPriceDataLoader).loadKreamRows();
    }
}
