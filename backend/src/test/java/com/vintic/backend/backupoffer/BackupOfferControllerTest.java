package com.vintic.backend.backupoffer;

import com.vintic.backend.backupoffer.domain.BackupOfferStatus;
import com.vintic.backend.backupoffer.dto.BackupOfferResponse;
import com.vintic.backend.backupoffer.service.BackupOfferQueryService;
import com.vintic.backend.common.exception.BackupOfferNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BackupOfferController.class)
class BackupOfferControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BackupOfferQueryService backupOfferQueryService;

    @Test
    void 조회_성공시_200과_FINAL_contract_필드를_반환한다() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        BackupOfferResponse response = new BackupOfferResponse(
                90L, 1L, BackupOfferStatus.WAITING,
                new BackupOfferResponse.Product(10L, "Nike Dunk Low Panda", "Dunk Low", "https://example.com/a.jpg"),
                100000L, 3000L, 103000L,
                now.plusHours(24), now
        );
        when(backupOfferQueryService.getBackupOffer(90L)).thenReturn(response);

        mockMvc.perform(get("/api/backup-offers/90").requestAttr("currentUserId", 2L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.backupOfferId").value(90))
                .andExpect(jsonPath("$.data.auctionId").value(1))
                .andExpect(jsonPath("$.data.status").value("WAITING"))
                .andExpect(jsonPath("$.data.purchasePrice").value(100000))
                .andExpect(jsonPath("$.data.shippingFee").value(3000))
                .andExpect(jsonPath("$.data.totalAmount").value(103000));
    }

    @Test
    void 존재하지_않는_backupOffer_조회는_404와_40403을_반환한다() throws Exception {
        when(backupOfferQueryService.getBackupOffer(999L))
                .thenThrow(new BackupOfferNotFoundException("존재하지 않는 차순위 제안입니다. backupOfferId: 999"));

        mockMvc.perform(get("/api/backup-offers/999").requestAttr("currentUserId", 2L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value(40403));
    }
}
