package com.vintic.backend.backupoffer.service;

import com.vintic.backend.backupoffer.domain.BackupOfferStatus;
import com.vintic.backend.backupoffer.repository.BackupOfferRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// OrderExpirationSchedulerTest와 동일한 구조.
@ExtendWith(MockitoExtension.class)
class BackupOfferExpirationSchedulerTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-08-18T22:00:00Z"), ZoneId.of("Asia/Seoul"));

    @Mock
    private BackupOfferRepository backupOfferRepository;

    @Mock
    private BackupOfferExpirationService backupOfferExpirationService;

    @Test
    void 후보로_조회된_모든_제안을_시도한다() {
        when(backupOfferRepository.findExpiredWaitingOfferIds(eq(BackupOfferStatus.WAITING), any()))
                .thenReturn(List.of(10L, 20L));

        new BackupOfferExpirationScheduler(backupOfferRepository, backupOfferExpirationService, FIXED_CLOCK, true)
                .expirePastDueOffers();

        verify(backupOfferExpirationService).expireIfDue(10L);
        verify(backupOfferExpirationService).expireIfDue(20L);
    }

    @Test
    void 한_건이_실패해도_나머지_후보_처리를_계속한다() {
        when(backupOfferRepository.findExpiredWaitingOfferIds(eq(BackupOfferStatus.WAITING), any()))
                .thenReturn(List.of(10L, 20L));
        org.mockito.Mockito.doThrow(new RuntimeException("락 대기 초과"))
                .when(backupOfferExpirationService).expireIfDue(10L);

        assertThatCode(
                () -> new BackupOfferExpirationScheduler(backupOfferRepository, backupOfferExpirationService, FIXED_CLOCK, true)
                        .expirePastDueOffers()
        ).doesNotThrowAnyException();

        verify(backupOfferExpirationService).expireIfDue(10L);
        verify(backupOfferExpirationService).expireIfDue(20L);
    }

    @Test
    void 꺼져있으면_후보_조회조차_하지_않는다() {
        new BackupOfferExpirationScheduler(backupOfferRepository, backupOfferExpirationService, FIXED_CLOCK, false)
                .expirePastDueOffers();

        verify(backupOfferRepository, never()).findExpiredWaitingOfferIds(any(), any());
        verify(backupOfferExpirationService, never()).expireIfDue(any());
    }

    @Test
    void 후보가_없으면_아무것도_시도하지_않는다() {
        when(backupOfferRepository.findExpiredWaitingOfferIds(eq(BackupOfferStatus.WAITING), any()))
                .thenReturn(List.of());

        new BackupOfferExpirationScheduler(backupOfferRepository, backupOfferExpirationService, FIXED_CLOCK, true)
                .expirePastDueOffers();

        verify(backupOfferExpirationService, times(0)).expireIfDue(any());
    }
}
