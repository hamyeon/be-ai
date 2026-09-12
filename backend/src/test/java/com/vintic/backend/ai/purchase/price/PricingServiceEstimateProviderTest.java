package com.vintic.backend.ai.purchase.price;

import com.vintic.backend.ai.purchase.model.ModelAliases;
import com.vintic.backend.product.pricing.PricingRequest;
import com.vintic.backend.product.pricing.PricingResult;
import com.vintic.backend.product.pricing.PricingService;
import com.vintic.backend.product.service.UsedMarketPriceProvider;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// PricingService 결과를 PriceEstimate로 옮기는 규칙과, 별칭 표로 매물 표기를 카탈로그 표기로 바꿔 묻는지를 고정한다.
class PricingServiceEstimateProviderTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-09-10T03:00:00Z"), ZoneId.of("Asia/Seoul"));

    private final PricingService pricingService = mock(PricingService.class);
    private final PricingServiceEstimateProvider provider = new PricingServiceEstimateProvider(
            pricingService, new UsedMarketPriceProvider(), new ModelAliases(), FIXED);

    @Test
    void 실거래_경로_결과는_USED_MARKET_시세와_IQR_표본수로_옮긴다() {
        when(pricingService.calculate(any())).thenReturn(result(130_000, 100_000, 0, 95_000, 160_000));

        Optional<PriceEstimate> estimate = provider.estimate(query("New Balance", "990v6", "A"));

        assertThat(estimate).isPresent();
        assertThat(estimate.get().estimatedPrice()).isEqualTo(130_000);
        assertThat(estimate.get().lowerBound()).isEqualTo(95_000);
        assertThat(estimate.get().upperBound()).isEqualTo(160_000);
        assertThat(estimate.get().source()).isEqualTo(PriceEstimate.Source.USED_MARKET);
        // 시세 CSV의 990 표본 수. 0이면 표본 수를 못 읽은 것이다.
        assertThat(estimate.get().sampleCount()).isPositive();
        assertThat(estimate.get().computedAt()).isEqualTo(java.time.LocalDateTime.now(FIXED));
    }

    @Test
    void KREAM_경로_결과는_KREAM_시세이고_범위가_없으면_추천가로_채운다() {
        when(pricingService.calculate(any())).thenReturn(result(200_000, 320_000, 320_000, 0, 0));

        PriceEstimate estimate = provider.estimate(query("Adidas", "Yeezy 350", "A")).orElseThrow();

        assertThat(estimate.source()).isEqualTo(PriceEstimate.Source.KREAM);
        assertThat(estimate.sampleCount()).isZero();
        assertThat(estimate.lowerBound()).isEqualTo(200_000);
        assertThat(estimate.upperBound()).isEqualTo(200_000);
    }

    @Test
    void 시세_정보_없음이면_비어_있다() {
        when(pricingService.calculate(any())).thenReturn(result(0, 0, 0, 0, 0));

        assertThat(provider.estimate(query("Zara", "운동화", "A"))).isEmpty();
    }

    @Test
    void 별칭_표가_아는_모델은_카탈로그_표기로_바꿔_묻는다() {
        when(pricingService.calculate(any())).thenReturn(result(90_000, 80_000, 0, 70_000, 110_000));
        ArgumentCaptor<PricingRequest> captor = ArgumentCaptor.forClass(PricingRequest.class);

        provider.estimate(new PriceEstimateQuery("나이키", "덩크 로우", "판다", 260, "A", "FULL_SET"));

        verify(pricingService).calculate(captor.capture());
        assertThat(captor.getValue().brand()).isEqualTo("Nike");
        assertThat(captor.getValue().modelName()).isEqualTo("Dunk Low");
        assertThat(captor.getValue().color()).isEqualTo("판다");
        assertThat(captor.getValue().size()).isEqualTo(260);
        assertThat(captor.getValue().conditionGrade()).isEqualTo("A");
    }

    @Test
    void 별칭_표가_모르는_모델은_원문_그대로_묻는다() {
        when(pricingService.calculate(any())).thenReturn(result(0, 0, 0, 0, 0));
        ArgumentCaptor<PricingRequest> captor = ArgumentCaptor.forClass(PricingRequest.class);

        provider.estimate(query("Asics", "Gel-1130", "B"));

        verify(pricingService).calculate(captor.capture());
        assertThat(captor.getValue().brand()).isEqualTo("Asics");
        assertThat(captor.getValue().modelName()).isEqualTo("Gel-1130");
    }

    private PriceEstimateQuery query(String brand, String model, String grade) {
        return new PriceEstimateQuery(brand, model, null, 270, grade, "SHOES_ONLY");
    }

    private PricingResult result(int recommended, int base, int kream, int min, int max) {
        return new PricingResult(recommended, base, kream, 0, min, max, "", "reason", List.of(), List.of());
    }
}
