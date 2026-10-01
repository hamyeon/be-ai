package com.vintic.backend.ai.purchase.api;

import com.vintic.backend.ai.purchase.dto.GoalCondition;
import com.vintic.backend.ai.purchase.dto.GoalDraft;
import com.vintic.backend.ai.purchase.parser.GoalParser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// POST /api/purchase-goals/parse 의 요청/응답 형식 회귀 테스트. 파서 자체는 mock이다.
@WebMvcTest(GoalParseController.class)
class GoalParseControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GoalParser goalParser;

    @Test
    void 자연어를_받아_GoalDraft_초안을_돌려준다() throws Exception {
        when(goalParser.parse(anyString())).thenReturn(new GoalDraft(
                "New Balance 990", "New Balance", "nb990", GoalCondition.A, 150_000L, null, null, 0.9,
                List.of("사이즈가 없습니다. 사이즈를 지정하지 않으면 모든 사이즈가 후보가 됩니다.")));

        mockMvc.perform(post("/api/purchase-goals/parse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"뉴발 990, A급 이상, 15만원 이하로 하나\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.modelQuery").value("New Balance 990"))
                .andExpect(jsonPath("$.data.brand").value("New Balance"))
                .andExpect(jsonPath("$.data.modelKey").value("nb990"))
                .andExpect(jsonPath("$.data.minCondition").value("A"))
                .andExpect(jsonPath("$.data.hardMaxAmount").value(150000))
                .andExpect(jsonPath("$.data.sizeKr").doesNotExist())
                .andExpect(jsonPath("$.data.freeTextConditions").doesNotExist())
                .andExpect(jsonPath("$.data.confidence").value(0.9))
                .andExpect(jsonPath("$.data.warnings[0]").isString())
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    void 빈_문장은_400이고_파서를_부르지_않는다() throws Exception {
        mockMvc.perform(post("/api/purchase-goals/parse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        verify(goalParser, never()).parse(anyString());
    }

    @Test
    void 너무_긴_문장은_400이다() throws Exception {
        mockMvc.perform(post("/api/purchase-goals/parse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"" + "가".repeat(301) + "\"}"))
                .andExpect(status().isBadRequest());

        verify(goalParser, never()).parse(anyString());
    }
}
