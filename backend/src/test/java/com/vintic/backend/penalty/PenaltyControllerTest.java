package com.vintic.backend.penalty;

import com.vintic.backend.penalty.domain.PenaltyType;
import com.vintic.backend.penalty.dto.MyPenaltyResponse;
import com.vintic.backend.penalty.service.PenaltyQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// FINAL contract §14.
@WebMvcTest(PenaltyController.class)
class PenaltyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PenaltyQueryService penaltyQueryService;

    @Test
    void 조회_성공시_200과_FINAL_contract_필드를_반환한다() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        MyPenaltyResponse response = new MyPenaltyResponse(
                2, true, now.plusDays(6), now,
                List.of(new MyPenaltyResponse.PenaltyItem(3L, PenaltyType.PAYMENT_EXPIRED, 1L, now))
        );
        when(penaltyQueryService.getMyPenalties(2L)).thenReturn(response);

        mockMvc.perform(get("/api/me/penalties").requestAttr("currentUserId", 2L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.noShowCount").value(2))
                .andExpect(jsonPath("$.data.bidRestricted").value(true))
                .andExpect(jsonPath("$.data.penalties[0].penaltyId").value(3))
                .andExpect(jsonPath("$.data.penalties[0].type").value("PAYMENT_EXPIRED"))
                .andExpect(jsonPath("$.data.penalties[0].auctionId").value(1));
    }

    @Test
    void 제한이_없으면_bidRestrictedUntil이_null이다() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        MyPenaltyResponse response = new MyPenaltyResponse(0, false, null, now, List.of());
        when(penaltyQueryService.getMyPenalties(2L)).thenReturn(response);

        mockMvc.perform(get("/api/me/penalties").requestAttr("currentUserId", 2L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.noShowCount").value(0))
                .andExpect(jsonPath("$.data.bidRestricted").value(false))
                .andExpect(jsonPath("$.data.bidRestrictedUntil").doesNotExist())
                .andExpect(jsonPath("$.data.penalties").isEmpty());
    }
}
