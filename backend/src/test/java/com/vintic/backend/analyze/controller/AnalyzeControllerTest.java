package com.vintic.backend.analyze.controller;

import com.vintic.backend.analyze.dto.AnalyzeResponse;
import com.vintic.backend.analyze.service.ProductAnalyzeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 리팩터링 전후로 /api/products/analyze의 요청/응답 형식이 그대로인지 확인하는 회귀 테스트
@WebMvcTest(AnalyzeController.class)
class AnalyzeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProductAnalyzeService productAnalyzeService;

    @Test
    void 분석_API_요청_응답_형식이_유지된다() throws Exception {
        MockMultipartFile image = new MockMultipartFile("images", "shoe.jpg", "image/jpeg", new byte[]{1, 2, 3});

        AnalyzeResponse response = new AnalyzeResponse(
                List.of("https://bucket.s3.amazonaws.com/shoe.jpg"),
                "Nike",
                "Air Jordan 1 Retro High OG",
                "Chicago Lost and Found",
                270,
                "사용감이 거의 없습니다.",
                "B"
        );
        when(productAnalyzeService.processImageAndAnalyze(anyList())).thenReturn(response);

        mockMvc.perform(multipart("/api/products/analyze").file(image))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.imageUrls[0]").value("https://bucket.s3.amazonaws.com/shoe.jpg"))
                .andExpect(jsonPath("$.data.brand").value("Nike"))
                .andExpect(jsonPath("$.data.modelName").value("Air Jordan 1 Retro High OG"))
                .andExpect(jsonPath("$.data.color").value("Chicago Lost and Found"))
                .andExpect(jsonPath("$.data.size").value(270))
                .andExpect(jsonPath("$.data.conditionDescription").value("사용감이 거의 없습니다."))
                .andExpect(jsonPath("$.data.conditionGrade").value("B"))
                .andExpect(jsonPath("$.error").doesNotExist());
    }
}
