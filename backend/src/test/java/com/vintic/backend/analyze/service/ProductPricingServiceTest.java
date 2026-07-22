package com.vintic.backend.analyze.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.analyze.domain.AnalysisStatus;
import com.vintic.backend.analyze.domain.ProductAnalysisSession;
import com.vintic.backend.analyze.domain.ProductAnalysisSessionRepository;
import com.vintic.backend.common.exception.AiApiException;
import com.vintic.backend.common.exception.AnalysisSessionNotFoundException;
import com.vintic.backend.product.dto.CalculatePriceRequest;
import com.vintic.backend.product.dto.CalculatePriceResponse;
import com.vintic.backend.product.pricing.PricingResult;
import com.vintic.backend.product.pricing.PricingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductPricingServiceTest {

    @Mock
    private PricingService pricingService;

    @Mock
    private ProductAnalysisSessionRepository sessionRepository;

    @Mock
    private AnalysisFailureRecorder failureRecorder;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private CalculatePriceRequest request() {
        return new CalculatePriceRequest(
                1L, "Nike", "Air Jordan 1 Retro High OG", "Chicago Lost and Found", 270, "B", "PARTIAL"
        );
    }

    private ProductAnalysisSession awaitingConfirmationSession() {
        ProductAnalysisSession session = ProductAnalysisSession.create();
        session.startVisionProcessing();
        session.completeVision("{}");
        return session;
    }

    @Test
    void 정상_요청이면_기존_API_응답_형식을_유지하고_확정입력값을_기록한다() {
        ProductAnalysisSession session = awaitingConfirmationSession();
        when(sessionRepository.findById(1L)).thenReturn(Optional.of(session));

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

        ProductPricingService sut = new ProductPricingService(pricingService, sessionRepository, failureRecorder, objectMapper);

        CalculatePriceResponse response = sut.calculatePrice(request());

        assertThat(response.recommendedPrice()).isEqualTo(300000);
        assertThat(response.kreamMatches().get(0).source()).isEqualTo("KREAM");
        assertThat(session.getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
        assertThat(session.getConfirmedInputJson()).contains("Nike", "Air Jordan 1 Retro High OG");
    }

    @Test
    void 세션이_없으면_AnalysisSessionNotFoundException을_던진다() {
        when(sessionRepository.findById(1L)).thenReturn(Optional.empty());

        ProductPricingService sut = new ProductPricingService(pricingService, sessionRepository, failureRecorder, objectMapper);

        assertThatThrownBy(() -> sut.calculatePrice(request()))
                .isInstanceOf(AnalysisSessionNotFoundException.class);
    }

    @Test
    void Pricing_호출이_실패하면_실패_기록을_남기고_원래_예외를_던진다() {
        ProductAnalysisSession session = awaitingConfirmationSession();
        when(sessionRepository.findById(1L)).thenReturn(Optional.of(session));
        when(pricingService.calculate(any()))
                .thenThrow(new AiApiException("시세 데이터를 불러오는 중 오류가 발생했습니다."));

        ProductPricingService sut = new ProductPricingService(pricingService, sessionRepository, failureRecorder, objectMapper);

        assertThatThrownBy(() -> sut.calculatePrice(request()))
                .isInstanceOf(AiApiException.class)
                .hasMessage("시세 데이터를 불러오는 중 오류가 발생했습니다.");

        verify(failureRecorder).recordPricingFailure(eq(session.getId()), anyString());
    }

    @Test
    void 실패_기록_저장_중_추가_오류가_나도_원래_Pricing_예외가_그대로_전파된다() {
        ProductAnalysisSession session = awaitingConfirmationSession();
        when(sessionRepository.findById(1L)).thenReturn(Optional.of(session));
        when(pricingService.calculate(any()))
                .thenThrow(new AiApiException("원래 Pricing 실패"));
        doThrow(new RuntimeException("실패 기록 저장 중 DB 오류"))
                .when(failureRecorder).recordPricingFailure(any(), anyString());

        ProductPricingService sut = new ProductPricingService(pricingService, sessionRepository, failureRecorder, objectMapper);

        assertThatThrownBy(() -> sut.calculatePrice(request()))
                .isInstanceOf(AiApiException.class)
                .hasMessage("원래 Pricing 실패");
    }

    @Test
    void 확정입력값_직렬화가_실패하면_PRICING_PROCESSING으로_전환되지_않는다() throws JsonProcessingException {
        ProductAnalysisSession session = awaitingConfirmationSession();
        when(sessionRepository.findById(1L)).thenReturn(Optional.of(session));

        ObjectMapper failingObjectMapper = mock(ObjectMapper.class);
        when(failingObjectMapper.writeValueAsString(any()))
                .thenThrow(new JsonProcessingException("직렬화 실패") {
                });

        ProductPricingService sut = new ProductPricingService(pricingService, sessionRepository, failureRecorder, failingObjectMapper);

        assertThatThrownBy(() -> sut.calculatePrice(request()))
                .isInstanceOf(AiApiException.class);

        assertThat(session.getStatus()).isEqualTo(AnalysisStatus.AWAITING_USER_CONFIRMATION);
        verify(sessionRepository, never()).save(any());
        verify(pricingService, never()).calculate(any());
    }
}
