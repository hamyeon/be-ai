package com.vintic.backend.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.analyze.service.ProductPricingService;
import com.vintic.backend.product.dto.CalculatePriceRequest;
import com.vintic.backend.product.dto.CalculatePriceResponse;
import com.vintic.backend.product.service.ProductRegistrationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Controller는 요청 수신/응답 반환만 담당하므로, ProductPricingService를 모킹해
// /api/products/calculate-price의 요청/응답 형식이 그대로인지만 확인하는 얇은 회귀 테스트
@WebMvcTest(ProductController.class)
class ProductControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ProductPricingService productPricingService;

    @MockitoBean
    private ProductRegistrationService productRegistrationService;

    @Test
    void 가격계산_API_요청_응답_형식이_유지된다() throws Exception {
        CalculatePriceRequest request = new CalculatePriceRequest(
                1L, "Nike", "Air Jordan 1 Retro High OG", "Chicago Lost and Found", 270, "B", "PARTIAL"
        );

        CalculatePriceResponse.MatchedMarketPrice matchedPrice = new CalculatePriceResponse.MatchedMarketPrice(
                "KREAM", "Nike", "Air Jordan 1 Retro High OG", "Chicago Lost and Found",
                270, "DS", "FULL", 400000, "https://kream.co.kr/products/1"
        );
        CalculatePriceResponse response = new CalculatePriceResponse(
                300000, 350000, 400000, 300000, 285000, 315000,
                "285,000원 ~ 315,000원", "테스트 사유",
                List.of(matchedPrice), List.of()
        );

        when(productPricingService.calculatePrice(any())).thenReturn(response);

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
