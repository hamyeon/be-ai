package com.vintic.backend.analyze.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.common.exception.AiApiException;
import com.vintic.backend.common.exception.AnalysisSessionNotFoundException;
import com.vintic.backend.common.exception.InvalidAnalysisStatusException;
import com.vintic.backend.product.dto.CalculatePriceRequest;
import com.vintic.backend.product.dto.CalculatePriceResponse;
import com.vintic.backend.product.pricing.PricingResult;
import com.vintic.backend.product.pricing.PricingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductPricingServiceTest {

    @Mock
    private PricingService pricingService;

    @Mock
    private PricingAttemptCoordinator coordinator;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private CalculatePriceRequest request() {
        return new CalculatePriceRequest(
                1L, "Nike", "Air Jordan 1 Retro High OG", "Chicago Lost and Found", 270, "B", "PARTIAL"
        );
    }

    private ProductPricingService newService() {
        return new ProductPricingService(pricingService, coordinator, objectMapper);
    }

    @Test
    void 정상_요청이면_기존_API_응답_형식을_유지하고_확정입력값으로_Pricing을_시작한다() {
        when(coordinator.tryCompletePricing(eq(1L), anyString())).thenReturn(true);

        PricingResult.MatchedMarketPrice matchedPrice = new PricingResult.MatchedMarketPrice(
                "KREAM", "Nike", "Air Jordan 1 Retro High OG", "Chicago Lost and Found",
                270, "DS", "FULL", 400000, "https://kream.co.kr/products/1"
        );
        PricingResult pricingResult = new PricingResult(
                300000, 350000, 400000, 300000, 285000, 315000,
                "285,000원 ~ 315,000원", "테스트 사유",
                List.of(matchedPrice), List.of()
        );
        when(pricingService.calculate(any())).thenReturn(pricingResult);

        CalculatePriceResponse response = newService().calculatePrice(request(), 1L);

        assertThat(response.recommendedPrice()).isEqualTo(300000);
        assertThat(response.kreamMatches().get(0).source()).isEqualTo("KREAM");

        verify(coordinator).startOwned(eq(1L), eq(1L), anyString());
        verify(coordinator).tryCompletePricing(eq(1L), anyString());
    }

    @Test
    void 세션이_없거나_타인의_세션이면_startOwned가_던지는_예외가_그대로_전파되고_Pricing을_호출하지_않는다() {
        doThrow(new AnalysisSessionNotFoundException("분석 세션을 찾을 수 없습니다. analysisId: 1"))
                .when(coordinator).startOwned(eq(1L), anyLong(), anyString());

        assertThatThrownBy(() -> newService().calculatePrice(request(), 2L))
                .isInstanceOf(AnalysisSessionNotFoundException.class);

        verifyNoInteractions(pricingService);
    }

    @Test
    void 취소되었거나_이미_진행중인_세션이면_startOwned가_던지는_InvalidAnalysisStatusException이_그대로_전파된다() {
        doThrow(new InvalidAnalysisStatusException("가격 계산을 요청할 수 없는 분석 상태입니다. 현재 상태: CANCELLED"))
                .when(coordinator).startOwned(eq(1L), anyLong(), anyString());

        assertThatThrownBy(() -> newService().calculatePrice(request(), 1L))
                .isInstanceOf(InvalidAnalysisStatusException.class);

        verifyNoInteractions(pricingService);
    }

    @Test
    void Pricing_호출이_실패하면_실패_기록을_남기고_원래_예외를_던진다() {
        when(pricingService.calculate(any()))
                .thenThrow(new AiApiException("시세 데이터를 불러오는 중 오류가 발생했습니다."));

        assertThatThrownBy(() -> newService().calculatePrice(request(), 1L))
                .isInstanceOf(AiApiException.class)
                .hasMessage("시세 데이터를 불러오는 중 오류가 발생했습니다.");

        verify(coordinator).tryFailPricing(eq(1L), anyString());
    }

    @Test
    void 실패_기록_저장_중_추가_오류가_나도_원래_Pricing_예외가_그대로_전파된다() {
        when(pricingService.calculate(any()))
                .thenThrow(new AiApiException("원래 Pricing 실패"));
        doThrow(new RuntimeException("실패 기록 저장 중 DB 오류"))
                .when(coordinator).tryFailPricing(any(), anyString());

        assertThatThrownBy(() -> newService().calculatePrice(request(), 1L))
                .isInstanceOf(AiApiException.class)
                .hasMessage("원래 Pricing 실패");
    }

    @Test
    void Pricing_호출_중_세션이_취소되면_결과를_저장하지_않고_InvalidAnalysisStatusException을_던진다() {
        PricingResult pricingResult = new PricingResult(
                300000, 350000, 400000, 300000, 285000, 315000,
                "285,000원 ~ 315,000원", "테스트 사유", List.of(), List.of()
        );
        when(pricingService.calculate(any())).thenReturn(pricingResult);
        when(coordinator.tryCompletePricing(eq(1L), anyString())).thenReturn(false);

        assertThatThrownBy(() -> newService().calculatePrice(request(), 1L))
                .isInstanceOf(InvalidAnalysisStatusException.class);
    }

    @Test
    void 확정입력값_직렬화가_실패하면_startOwned를_호출하지_않는다() throws JsonProcessingException {
        ObjectMapper failingObjectMapper = mock(ObjectMapper.class);
        when(failingObjectMapper.writeValueAsString(any()))
                .thenThrow(new JsonProcessingException("직렬화 실패") {
                });

        ProductPricingService sut = new ProductPricingService(pricingService, coordinator, failingObjectMapper);

        assertThatThrownBy(() -> sut.calculatePrice(request(), 1L))
                .isInstanceOf(AiApiException.class);

        verify(coordinator, never()).startOwned(any(), any(), any());
        verify(pricingService, never()).calculate(any());
    }
}
