package com.vintic.backend.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.analyze.domain.ProductAnalysisSession;
import com.vintic.backend.analyze.domain.ProductAnalysisSessionRepository;
import com.vintic.backend.product.dto.CalculatePriceRequest;
import com.vintic.backend.product.pricing.PricingRequest;
import com.vintic.backend.product.pricing.PricingResult;
import com.vintic.backend.product.pricing.PricingService;
import com.vintic.backend.product.service.ProductRegistrationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 리팩터링 전후로 /api/products/calculate-price의 요청/응답 형식이 그대로인지 확인하는 회귀 테스트
@WebMvcTest(ProductController.class)
class ProductControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private PricingService pricingService;

    @MockitoBean
    private ProductRegistrationService productRegistrationService;

    @MockitoBean
    private ProductAnalysisSessionRepository sessionRepository;

    @Test
    void 가격계산_API_요청_응답_형식이_유지된다() throws Exception {
        ProductAnalysisSession session = ProductAnalysisSession.create();
        session.startVisionProcessing();
        session.completeVision("{}");
        when(sessionRepository.findById(1L)).thenReturn(Optional.of(session));

        CalculatePriceRequest request = new CalculatePriceRequest(
                1L, "Nike", "Air Jordan 1 Retro High OG", "Chicago Lost and Found", 270, "B", "PARTIAL"
        );

        PricingResult.MatchedMarketPrice matchedPrice = new PricingResult.MatchedMarketPrice(
                "KREAM", "Nike", "Air Jordan 1 Retro High OG", "Chicago Lost and Found",
                270, "DS", "FULL", 400000, "https://kream.co.kr/products/1"
        );
        PricingResult pricingResult = new PricingResult(
                300000, 350000, 400000, 300000, 285000, 315000,
                "285,000원 ~ 315,000원", "테스트 사유",
                List.of(matchedPrice), List.of()
        );

        when(pricingService.calculate(new PricingRequest(
                "Nike", "Air Jordan 1 Retro High OG", "Chicago Lost and Found", 270, "B", "PARTIAL"
        ))).thenReturn(pricingResult);

        mockMvc.perform(post("/api/products/calculate-price")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.recommendedPrice").value(300000))
                .andExpect(jsonPath("$.data.baseMarketPrice").value(350000))
                .andExpect(jsonPath("$.data.priceRange").value("285,000원 ~ 315,000원"))
                .andExpect(jsonPath("$.data.reason").value("테스트 사유"))
                .andExpect(jsonPath("$.data.kreamMatches[0].source").value("KREAM"))
                .andExpect(jsonPath("$.data.kreamMatches[0].price").value(400000))
                .andExpect(jsonPath("$.error").doesNotExist());
    }
}
