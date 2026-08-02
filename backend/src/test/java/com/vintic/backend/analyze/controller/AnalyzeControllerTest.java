package com.vintic.backend.analyze.controller;

import com.vintic.backend.analyze.dto.AnalysisStatusResponse;
import com.vintic.backend.analyze.dto.AnalyzeAcceptedResponse;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 비동기 분석 API(202 Accepted + taskId 발급, 상태 폴링)의 요청/응답 형식을 확인하는 회귀 테스트
@WebMvcTest(AnalyzeController.class)
class AnalyzeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProductAnalyzeService productAnalyzeService;

    @Test
    void 분석_요청은_202와_taskId를_반환한다() throws Exception {
        MockMultipartFile image = new MockMultipartFile("images", "shoe.jpg", "image/jpeg", new byte[]{1, 2, 3});

        AnalyzeAcceptedResponse response = new AnalyzeAcceptedResponse(1L, "QUEUED");
        when(productAnalyzeService.submitForAnalysis(anyList())).thenReturn(response);

        mockMvc.perform(multipart("/api/products/analyze").file(image))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.analysisId").value(1))
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    void 상태_조회는_taskId로_분석_진행상황을_반환한다() throws Exception {
        AnalysisStatusResponse response = new AnalysisStatusResponse(
                1L,
                "AWAITING_USER_CONFIRMATION",
                List.of("https://bucket.s3.amazonaws.com/shoe.jpg"),
                "Nike",
                "Air Jordan 1 Retro High OG",
                "Chicago Lost and Found",
                270,
                "사용감이 거의 없습니다.",
                "B",
                null,
                null
        );
        when(productAnalyzeService.getStatus(1L)).thenReturn(response);

        mockMvc.perform(get("/api/products/analyze/{taskId}", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.analysisId").value(1))
                .andExpect(jsonPath("$.data.status").value("AWAITING_USER_CONFIRMATION"))
                .andExpect(jsonPath("$.data.brand").value("Nike"))
                .andExpect(jsonPath("$.data.modelName").value("Air Jordan 1 Retro High OG"))
                .andExpect(jsonPath("$.data.conditionGrade").value("B"))
                .andExpect(jsonPath("$.error").doesNotExist());
    }
}
