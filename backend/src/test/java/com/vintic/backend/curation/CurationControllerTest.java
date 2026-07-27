package com.vintic.backend.curation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// CurationService는 의존성 없는 순수 서비스라 목킹 대신 실제 빈을 그대로 사용한다.
@WebMvcTest(CurationController.class)
@Import(CurationService.class)
class CurationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 큐레이션_목록_응답_스키마를_확인한다() throws Exception {
        mockMvc.perform(get("/api/curations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].curationId").exists())
                .andExpect(jsonPath("$.data[0].title").exists())
                .andExpect(jsonPath("$.data[0].items").isArray())
                .andExpect(jsonPath("$.data[0].items[0].productId").exists())
                .andExpect(jsonPath("$.data[0].items[0].brand").exists())
                .andExpect(jsonPath("$.data[0].items[0].price").exists());
    }
}
