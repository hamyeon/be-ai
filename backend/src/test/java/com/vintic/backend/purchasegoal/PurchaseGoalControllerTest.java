package com.vintic.backend.purchasegoal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.common.exception.InvalidPurchaseGoalException;
import com.vintic.backend.purchasegoal.dto.CreatePurchaseGoalRequest;
import com.vintic.backend.purchasegoal.dto.PurchaseGoalResponse;
import com.vintic.backend.purchasegoal.service.PurchaseGoalCommandService;
import com.vintic.backend.purchasegoal.service.PurchaseGoalQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PurchaseGoalController.class)
class PurchaseGoalControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private PurchaseGoalCommandService purchaseGoalCommandService;

    @MockitoBean
    private PurchaseGoalQueryService purchaseGoalQueryService;

    private CreatePurchaseGoalRequest validRequest() {
        return new CreatePurchaseGoalRequest(
                "New Balance", "nb990", "뉴발란스 990", "A",
                270, 150000L, "박스 있으면 좋음", OffsetDateTime.now().plusDays(7)
        );
    }

    @Test
    void 직접_입력으로_등록_성공시_201과_ACTIVE_상태를_반환한다() throws Exception {
        PurchaseGoalResponse response = new PurchaseGoalResponse(
                1L, 2L, "New Balance", "nb990", "뉴발란스 990", "A",
                270, 150000L, "박스 있으면 좋음", OffsetDateTime.now().plusDays(7),
                "ACTIVE", null, OffsetDateTime.now(), 0, 0
        );
        when(purchaseGoalCommandService.createGoal(any(), eq(2L))).thenReturn(response);

        mockMvc.perform(post("/api/purchase-goals")
                        .requestAttr("currentUserId", 2L)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.userId").value(2))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.currentAuctionId").doesNotExist());
    }

    @Test
    void 인증된_사용자_id로만_등록되고_바디에는_userId_필드가_없다() throws Exception {
        PurchaseGoalResponse response = new PurchaseGoalResponse(
                1L, 7L, "New Balance", "nb990", "뉴발란스 990", "A",
                270, 150000L, null, OffsetDateTime.now().plusDays(7),
                "ACTIVE", null, OffsetDateTime.now(), 0, 0
        );
        when(purchaseGoalCommandService.createGoal(any(), eq(7L))).thenReturn(response);

        mockMvc.perform(post("/api/purchase-goals")
                        .requestAttr("currentUserId", 7L)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isCreated());

        verify(purchaseGoalCommandService).createGoal(any(), eq(7L));
    }

    @Test
    void 예산이_0이하이면_400과_40001을_반환한다() throws Exception {
        CreatePurchaseGoalRequest invalid = new CreatePurchaseGoalRequest(
                "New Balance", "nb990", "뉴발란스 990", "A",
                270, 0L, null, OffsetDateTime.now().plusDays(7)
        );

        mockMvc.perform(post("/api/purchase-goals")
                        .requestAttr("currentUserId", 2L)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(40001));
    }

    @Test
    void deadline이_없으면_400과_40001을_반환한다() throws Exception {
        CreatePurchaseGoalRequest invalid = new CreatePurchaseGoalRequest(
                "New Balance", "nb990", "뉴발란스 990", "A",
                270, 150000L, null, null
        );

        mockMvc.perform(post("/api/purchase-goals")
                        .requestAttr("currentUserId", 2L)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(40001));
    }

    @Test
    void 브랜드와_사이즈가_없어도_유효한_요청이면_201로_등록된다() throws Exception {
        CreatePurchaseGoalRequest noBrandNoSize = new CreatePurchaseGoalRequest(
                null, "nb990", "뉴발란스 990", "A",
                null, 150000L, null, OffsetDateTime.now().plusDays(7)
        );
        PurchaseGoalResponse response = new PurchaseGoalResponse(
                1L, 2L, null, "nb990", "뉴발란스 990", "A",
                null, 150000L, null, OffsetDateTime.now().plusDays(7),
                "ACTIVE", null, OffsetDateTime.now(), 0, 0
        );
        when(purchaseGoalCommandService.createGoal(any(), eq(2L))).thenReturn(response);

        mockMvc.perform(post("/api/purchase-goals")
                        .requestAttr("currentUserId", 2L)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(noBrandNoSize)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.brand").doesNotExist())
                .andExpect(jsonPath("$.data.sizeKr").doesNotExist());
    }

    @Test
    void deadline이_과거이면_400과_40005를_반환한다() throws Exception {
        when(purchaseGoalCommandService.createGoal(any(), eq(2L)))
                .thenThrow(new InvalidPurchaseGoalException("마감 시각은 현재 이후여야 합니다."));

        CreatePurchaseGoalRequest pastDeadline = new CreatePurchaseGoalRequest(
                "New Balance", "nb990", "뉴발란스 990", "A",
                270, 150000L, null, OffsetDateTime.now().minusDays(1)
        );

        mockMvc.perform(post("/api/purchase-goals")
                        .requestAttr("currentUserId", 2L)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(pastDeadline)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(40005));
    }
}
