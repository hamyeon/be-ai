package com.vintic.backend.common.auth.mock;

import com.vintic.backend.analyze.service.ProductPricingService;
import com.vintic.backend.config.MockAuthWebConfig;
import com.vintic.backend.product.ProductController;
import com.vintic.backend.product.service.ProductRegistrationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// local profile에서 MockAuthInterceptor가 실제로 걸리는지 확인하는 슬라이스 테스트.
// 기존 엔드포인트(GET /api/products)를 그대로 사용해 인터셉터가 컨트롤러 앞단에서 헤더를 검증하는지 본다.
@WebMvcTest(ProductController.class)
@Import({MockAuthWebConfig.class, MockUserRegistry.class})
@ActiveProfiles("local")
class MockAuthInterceptorTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProductPricingService productPricingService;

    @MockitoBean
    private ProductRegistrationService productRegistrationService;

    @Test
    void 헤더가_없으면_401을_반환한다() throws Exception {
        mockMvc.perform(get("/api/products"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value(40101));
    }

    @Test
    void 헤더가_숫자가_아니면_401을_반환한다() throws Exception {
        mockMvc.perform(get("/api/products").header("X-User-Id", "abc"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value(40101));
    }

    @Test
    void 존재하지_않는_유저면_401을_반환한다() throws Exception {
        mockMvc.perform(get("/api/products").header("X-User-Id", "999"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value(40101));
    }

    @Test
    void 존재하는_더미유저면_통과한다() throws Exception {
        when(productRegistrationService.getProducts()).thenReturn(List.of());

        mockMvc.perform(get("/api/products").header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }
}
