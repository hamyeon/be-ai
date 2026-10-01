package com.vintic.backend.backupoffer;

import com.vintic.backend.backupoffer.domain.BackupOfferStatus;
import com.vintic.backend.backupoffer.dto.BackupOfferAcceptResponse;
import com.vintic.backend.backupoffer.dto.BackupOfferDeclineResponse;
import com.vintic.backend.backupoffer.dto.BackupOfferResponse;
import com.vintic.backend.backupoffer.service.BackupOfferQueryService;
import com.vintic.backend.backupoffer.service.BackupOfferService;
import com.vintic.backend.common.exception.BackupOfferAccessDeniedException;
import com.vintic.backend.common.exception.BackupOfferAlreadyResolvedException;
import com.vintic.backend.common.exception.BackupOfferExpiredException;
import com.vintic.backend.common.exception.BackupOfferNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BackupOfferController.class)
class BackupOfferControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BackupOfferQueryService backupOfferQueryService;

    @MockitoBean
    private BackupOfferService backupOfferService;

    @Test
    void 조회_성공시_200과_FINAL_contract_필드를_반환한다() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        BackupOfferResponse response = new BackupOfferResponse(
                90L, 1L, BackupOfferStatus.WAITING,
                new BackupOfferResponse.Product(10L, "Nike Dunk Low Panda", "Dunk Low", "https://example.com/a.jpg"),
                100000L, 3000L, 103000L,
                now.plusHours(24), now
        );
        when(backupOfferQueryService.getBackupOffer(90L, 2L)).thenReturn(response);

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
        when(backupOfferQueryService.getBackupOffer(999L, 2L))
                .thenThrow(new BackupOfferNotFoundException("존재하지 않는 차순위 제안입니다. backupOfferId: 999"));

        mockMvc.perform(get("/api/backup-offers/999").requestAttr("currentUserId", 2L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value(40403));
    }

    @Test
    void 본인_명의가_아닌_제안_조회는_403과_40305를_반환한다() throws Exception {
        when(backupOfferQueryService.getBackupOffer(90L, 2L))
                .thenThrow(new BackupOfferAccessDeniedException("본인 명의의 차순위 제안이 아닙니다. backupOfferId: 90"));

        mockMvc.perform(get("/api/backup-offers/90").requestAttr("currentUserId", 2L))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value(40305));
    }

    @Test
    void 수락_성공시_201과_orderId를_반환한다() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        BackupOfferAcceptResponse response = new BackupOfferAcceptResponse(
                90L, BackupOfferStatus.ACCEPTED, 55L, 103000L, now.plusHours(24)
        );
        when(backupOfferService.accept(90L, 2L, "key-1")).thenReturn(response);

        mockMvc.perform(post("/api/backup-offers/90/accept")
                        .requestAttr("currentUserId", 2L)
                        .header("Idempotency-Key", "key-1"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.backupOfferId").value(90))
                .andExpect(jsonPath("$.data.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.data.orderId").value(55))
                .andExpect(jsonPath("$.data.totalAmount").value(103000));
    }

    @Test
    void Idempotency_Key_헤더가_없으면_400과_40004를_반환한다() throws Exception {
        mockMvc.perform(post("/api/backup-offers/90/accept").requestAttr("currentUserId", 2L))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(40004));
    }

    @Test
    void 만료된_제안을_수락하면_409와_40911을_반환한다() throws Exception {
        when(backupOfferService.accept(90L, 2L, "key-1"))
                .thenThrow(new BackupOfferExpiredException("차순위 구매 기한이 만료되었습니다. backupOfferId: 90"));

        mockMvc.perform(post("/api/backup-offers/90/accept")
                        .requestAttr("currentUserId", 2L)
                        .header("Idempotency-Key", "key-1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value(40911));
    }

    @Test
    void 이미_처리된_제안을_수락하면_409와_40912를_반환한다() throws Exception {
        when(backupOfferService.accept(90L, 2L, "key-1"))
                .thenThrow(new BackupOfferAlreadyResolvedException("이미 처리된 제안입니다. backupOfferId: 90"));

        mockMvc.perform(post("/api/backup-offers/90/accept")
                        .requestAttr("currentUserId", 2L)
                        .header("Idempotency-Key", "key-1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value(40912));
    }

    @Test
    void 본인_명의가_아닌_제안_수락은_403과_40305를_반환한다() throws Exception {
        when(backupOfferService.accept(90L, 2L, "key-1"))
                .thenThrow(new BackupOfferAccessDeniedException("본인 명의의 차순위 제안이 아닙니다. backupOfferId: 90"));

        mockMvc.perform(post("/api/backup-offers/90/accept")
                        .requestAttr("currentUserId", 2L)
                        .header("Idempotency-Key", "key-1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value(40305));
    }

    @Test
    void 거절_성공시_200과_DECLINED를_반환한다() throws Exception {
        when(backupOfferService.decline(90L, 2L)).thenReturn(new BackupOfferDeclineResponse(90L, BackupOfferStatus.DECLINED));

        mockMvc.perform(post("/api/backup-offers/90/decline").requestAttr("currentUserId", 2L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.backupOfferId").value(90))
                .andExpect(jsonPath("$.data.status").value("DECLINED"));
    }

    @Test
    void 이미_처리된_제안을_거절하면_409와_40912를_반환한다() throws Exception {
        when(backupOfferService.decline(90L, 2L))
                .thenThrow(new BackupOfferAlreadyResolvedException("이미 처리된 제안입니다. backupOfferId: 90"));

        mockMvc.perform(post("/api/backup-offers/90/decline").requestAttr("currentUserId", 2L))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value(40912));
    }

    @Test
    void 본인_명의가_아닌_제안_거절은_403과_40305를_반환한다() throws Exception {
        when(backupOfferService.decline(90L, 2L))
                .thenThrow(new BackupOfferAccessDeniedException("본인 명의의 차순위 제안이 아닙니다. backupOfferId: 90"));

        mockMvc.perform(post("/api/backup-offers/90/decline").requestAttr("currentUserId", 2L))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value(40305));
    }
}
