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

    @Mock
    private ColorPremiumProvider colorPremiumProvider;

    // 상태 계수는 실측 CSV까지 실제로 읽어야 비율(#61 값)이 의미를 갖는다
    private final ConditionRateProvider conditionRateProvider = new ConditionRateProvider();

    // 구성품 계수도 같은 이유로 실측 CSV를 읽는다(#104)
    private final ComponentRateProvider componentRateProvider = new ComponentRateProvider();

    private PriceCalculationService newService() {
        return new PriceCalculationService(
                marketPriceDataLoader, conditionRateProvider, usedMarketPriceProvider,
                colorPremiumProvider, componentRateProvider);
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
    void 상태가_좋으면_전체_매물_대비_실측_비율만큼_오른다() {
        when(usedMarketPriceProvider.find("Nike", "Dunk Low"))
                .thenReturn(Optional.of(dunkLowMarket()));

        CalculatePriceResponse ds = newService().calculate(request("Nike", "Dunk Low", "DS"));
        CalculatePriceResponse unknown = newService().calculate(request("Nike", "Dunk Low", "UNKNOWN"));

        // DS 실측 계수 / 전체 매물 실측 계수(ALL)의 비율로만 움직인다 - 지어낸 숫자가 없다.
        // 기대값을 CSV에서 직접 읽어, 재측정으로 계수가 바뀌어도 조합 방식만 검증한다.
        double expected = conditionRateProvider.resolve("Dunk Low", "DS").rate()
                / conditionRateProvider.resolve(null, "ALL").rate();
        double ratio = (double) ds.recommendedPrice() / unknown.recommendedPrice();
        assertThat(ratio).isCloseTo(expected, within(0.05));
    }

    @Test
    void 등급_사다리는_실측값과_기본값이_섞여도_서열을_지킨다() {
        when(usedMarketPriceProvider.find("Nike", "Dunk Low"))
                .thenReturn(Optional.of(dunkLowMarket()));

        PriceCalculationService service = newService();
        int ds = service.calculate(request("Nike", "Dunk Low", "DS")).recommendedPrice();
        int s = service.calculate(request("Nike", "Dunk Low", "S")).recommendedPrice();
        int a = service.calculate(request("Nike", "Dunk Low", "A")).recommendedPrice();
        int b = service.calculate(request("Nike", "Dunk Low", "B")).recommendedPrice();
        int c = service.calculate(request("Nike", "Dunk Low", "C")).recommendedPrice();

        // DS/A/B는 실측, S/C는 기본값인데도 가격 서열이 뒤집히면 안 된다
        assertThat(ds).isGreaterThan(s);
        assertThat(s).isGreaterThan(a);
        assertThat(a).isGreaterThan(b);
        assertThat(b).isGreaterThan(c);
    }

    @Test
    void 실측_계수를_쓴_등급은_근거를_밝힌다() {
        when(usedMarketPriceProvider.find("Nike", "Dunk Low"))
                .thenReturn(Optional.of(dunkLowMarket()));

        // A는 매물 설명 재분류로 실측한 공통값(#86), C는 서열 역전으로 제외돼 기본값
        assertThat(newService().calculate(request("Nike", "Dunk Low", "A")).reason())
                .contains("공통값");
        assertThat(newService().calculate(request("Nike", "Dunk Low", "C")).reason())
                .contains("기본값");
    }

    @Test
    void 같은_색상_계열_시세가_있으면_그것을_기준으로_계산한다() {
        // #93: "그레이 가젤"은 가젤 전체가 아니라 그레이 가젤들의 시세와 비교한다
        when(usedMarketPriceProvider.find("Nike", "Dunk Low"))
                .thenReturn(Optional.of(dunkLowMarket()));
        when(usedMarketPriceProvider.findColor("Nike", "Dunk Low", "Blue"))
                .thenReturn(Optional.of(new UsedMarketPriceProvider.ColorPrice(
                        "nike", "dunklow", "blue", 10, 85_000, 60_000, 110_000)));

        CalculatePriceResponse response = newService().calculate(
                new CalculatePriceRequest(1L, "Nike", "Dunk Low", "Blue", 270, "UNKNOWN", "FULL"));

        // 모델 전체 중앙값(40,000)이 아니라 블루 버킷 중앙값(85,000)이 기준
        assertThat(response.recommendedPrice()).isEqualTo(85_000);
        assertThat(response.minRecommendedPrice()).isEqualTo(60_000);
        assertThat(response.reason()).contains("같은 색상 계열(blue)", "10건");
    }

    @Test
    void 색상_버킷이_없으면_KREAM_색상_프리미엄으로_보정한다() {
        // 중간 폴백(#93): 당근 색상 표본은 없지만 KREAM에서 그 색이 비싼 걸 아는 경우
        when(usedMarketPriceProvider.find("Nike", "Dunk Low"))
                .thenReturn(Optional.of(dunkLowMarket()));
        when(usedMarketPriceProvider.findColor("Nike", "Dunk Low", "Brown"))
                .thenReturn(Optional.empty());
        when(colorPremiumProvider.find("Nike", "Dunk Low", "Brown"))
                .thenReturn(Optional.of(new ColorPremiumProvider.ColorPremium(
                        "nike", "dunklow", "brown", 1.2, 5)));

        CalculatePriceResponse response = newService().calculate(
                new CalculatePriceRequest(1L, "Nike", "Dunk Low", "Brown", 270, "UNKNOWN", "FULL"));

        // 모델 중앙값 40,000 x 1.2 = 48,000
        assertThat(response.recommendedPrice()).isEqualTo(48_000);
        assertThat(response.reason()).contains("모델 평균 대비 +20%", "KREAM 체결 5건");
    }

    @Test
    void 색상_버킷이_없으면_모델_시세로_폴백한다() {
        when(usedMarketPriceProvider.find("Nike", "Dunk Low"))
                .thenReturn(Optional.of(dunkLowMarket()));
        when(usedMarketPriceProvider.findColor("Nike", "Dunk Low", "Panda"))
                .thenReturn(Optional.empty());

        CalculatePriceResponse response = newService().calculate(request("Nike", "Dunk Low", "UNKNOWN"));

        // 색상 시세는 설 때만 쓴다 - 없으면 기존 그대로, 나빠지는 경로 없음
        assertThat(response.recommendedPrice()).isEqualTo(40_000);
        assertThat(response.reason()).contains("중고 매물 116건");
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

    private MarketPriceDataLoader.MarketPriceRow row(String source, int price) {
        return new MarketPriceDataLoader.MarketPriceRow(
                source, "Nike", "없는모델", "Panda", 270, "DS", true, price, "https://example.com");
    }

    @Test
    void 이순위_기준_시세는_KREAM_단독이다() {
        // #89 실측: 당근 실거래 예측 오차가 KREAM 단독 25% vs 혼합(0.7/0.3) 38%.
        // eBay(해외 호가)를 섞을수록 나빠져서 기준 시세에서 뺐다.
        when(usedMarketPriceProvider.find(anyString(), anyString())).thenReturn(Optional.empty());
        when(marketPriceDataLoader.loadKreamRows()).thenReturn(List.of(row("KREAM", 100_000)));
        when(marketPriceDataLoader.loadEbayRows()).thenReturn(List.of(row("EBAY", 300_000)));

        CalculatePriceResponse response = newService().calculate(request("Nike", "없는모델", "DS"));

        // 혼합이었다면 기준 시세가 160,000이 됐을 것이다
        assertThat(response.baseMarketPrice()).isEqualTo(100_000);
        // eBay는 계산에서 빠지되 참고 정보로는 남는다
        assertThat(response.ebayAveragePrice()).isEqualTo(300_000);
        assertThat(response.reason()).contains("참고로만 표시");
    }

    @Test
    void eBay만_있으면_가격을_만들지_않는다() {
        // eBay 단독 예측 오차 155% - 틀린 값보다 "없음"이 낫다
        when(usedMarketPriceProvider.find(anyString(), anyString())).thenReturn(Optional.empty());
        when(marketPriceDataLoader.loadKreamRows()).thenReturn(List.of());
        when(marketPriceDataLoader.loadEbayRows()).thenReturn(List.of(row("EBAY", 300_000)));

        CalculatePriceResponse response = newService().calculate(request("Nike", "없는모델", "DS"));

        assertThat(response.recommendedPrice()).isZero();
        assertThat(response.priceRange()).isEqualTo("시세 정보 없음");
        // 참고용으로는 보여준다
        assertThat(response.ebayAveragePrice()).isEqualTo(300_000);
        assertThat(response.ebayMatches()).hasSize(1);
    }

    private CalculatePriceRequest componentRequest(String componentStatus) {
        return new CalculatePriceRequest(1L, "Nike", "Dunk Low", "Panda", 270, "UNKNOWN", componentStatus);
    }

    @Test
    void 구성품이_없으면_전체_매물_시세보다_낮게_추천한다() {
        // #104: 계수가 실측값(NONE 0.900)으로 바뀌었다. 기존 기본값은 0.95로, 실제 갭의
        // 절반만 반영하고 있었다.
        when(usedMarketPriceProvider.find("Nike", "Dunk Low"))
                .thenReturn(Optional.of(dunkLowMarket()));

        CalculatePriceResponse response = newService().calculate(componentRequest("NONE"));

        assertThat(response.recommendedPrice()).isEqualTo(36_000);
        assertThat(response.reason()).contains("당근마켓 실거래");
    }

    @Test
    void 구성품_미상은_보정없이_전체_매물_중앙값을_쓴다() {
        // #104 문제 3: 그 전에는 PARTIAL도 미상도 0.97이라 계산이 같았다.
        // 미상은 "판매자가 안 적음"이지 "구성품이 일부"라는 판단이 아니다 - 상태 UNKNOWN과 같은 처리.
        when(usedMarketPriceProvider.find("Nike", "Dunk Low"))
                .thenReturn(Optional.of(dunkLowMarket()));

        PriceCalculationService service = newService();
        CalculatePriceResponse unknown = service.calculate(componentRequest(null));
        CalculatePriceResponse partial = service.calculate(componentRequest("PARTIAL"));

        assertThat(unknown.recommendedPrice()).isEqualTo(40_000);
        assertThat(unknown.reason()).contains("판단하기 어려워");
        // 계수가 같더라도 문구와 경로는 갈라져 있어야 한다
        assertThat(partial.reason()).contains("일부 포함");
    }

    @Test
    void 구성품_계수는_서열을_지킨다() {
        when(usedMarketPriceProvider.find("Nike", "Dunk Low"))
                .thenReturn(Optional.of(dunkLowMarket()));

        PriceCalculationService service = newService();
        int full = service.calculate(componentRequest("FULL")).recommendedPrice();
        int partial = service.calculate(componentRequest("PARTIAL")).recommendedPrice();
        int none = service.calculate(componentRequest("NONE")).recommendedPrice();

        assertThat(full).isGreaterThanOrEqualTo(partial);
        assertThat(partial).isGreaterThan(none);
    }

    @Test
    void 구성품_반영률도_실측인지_기본값인지_밝힌다() {
        when(usedMarketPriceProvider.find("Nike", "Dunk Low"))
                .thenReturn(Optional.of(dunkLowMarket()));

        PriceCalculationService service = newService();

        // NONE은 실측(n=1195), FULL은 셀별 편차가 커 미채택이라 기본값이다
        assertThat(service.calculate(componentRequest("NONE")).reason())
                .contains("당근마켓 실거래").contains("건으로 산출한 값입니다");
        assertThat(service.calculate(componentRequest("FULL")).reason())
                .contains("기본값을 사용했습니다");
    }

    @Test
    void KREAM_경로는_풀박스_기준가라_구성품_계수를_그대로_쓰지_않는다() {
        // KREAM은 새제품 시세라 기준 모집단이 풀박스다. 중고 시세 경로의 계수(전체 대비)를
        // 그대로 곱하면 모든 매물을 풀박스로 계산하게 된다.
        when(usedMarketPriceProvider.find(anyString(), anyString())).thenReturn(Optional.empty());
        when(marketPriceDataLoader.loadKreamRows()).thenReturn(List.of(row("KREAM", 100_000)));
        when(marketPriceDataLoader.loadEbayRows()).thenReturn(List.of());

        PriceCalculationService service = newService();
        CalculatePriceResponse full = service.calculate(
                new CalculatePriceRequest(1L, "Nike", "없는모델", "Panda", 270, "DS", "FULL"));

        // 풀박스는 기준가를 그대로 받는다(계수 1.00)
        assertThat(full.recommendedPrice()).isEqualTo(
                (int) Math.round(100_000 * conditionRateProvider.resolve("없는모델", "DS").rate() / 1000.0) * 1000);
    }
}
