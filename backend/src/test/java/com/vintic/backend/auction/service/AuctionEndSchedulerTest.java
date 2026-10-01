package com.vintic.backend.auction.service;

import com.vintic.backend.auction.domain.AuctionStatus;
import com.vintic.backend.auction.repository.AuctionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// AuctionStartSchedulerTest와 동일한 구조. 실제 종료/settlement 로직 검증은
// AuctionEndServiceTest(#73-2)가 담당하므로 여기서 중복하지 않는다.
@ExtendWith(MockitoExtension.class)
class AuctionEndSchedulerTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-08-18T22:00:00Z"), ZoneId.of("Asia/Seoul"));

    @Mock
    private AuctionRepository auctionRepository;

    @Mock
    private AuctionEndService auctionEndService;

    @Test
    void 후보로_조회된_모든_Auction을_시도한다() {
        when(auctionRepository.findLiveDueForEnd(eq(AuctionStatus.LIVE), any(), any()))
                .thenReturn(List.of(10L, 20L));

        new AuctionEndScheduler(auctionRepository, auctionEndService, FIXED_CLOCK, true, 100).endDueAuctions();

        verify(auctionEndService).endIfDue(10L);
        verify(auctionEndService).endIfDue(20L);
    }

    @Test
    void 설정된_batch_size가_Pageable에_그대로_적용된다() {
        when(auctionRepository.findLiveDueForEnd(eq(AuctionStatus.LIVE), any(), any()))
                .thenReturn(List.of());

        new AuctionEndScheduler(auctionRepository, auctionEndService, FIXED_CLOCK, true, 30).endDueAuctions();

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(auctionRepository).findLiveDueForEnd(eq(AuctionStatus.LIVE), any(), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(30);
    }

    @Test
    void now는_주입된_Clock_기준으로_조회한다() {
        when(auctionRepository.findLiveDueForEnd(eq(AuctionStatus.LIVE), any(), any()))
                .thenReturn(List.of());

        new AuctionEndScheduler(auctionRepository, auctionEndService, FIXED_CLOCK, true, 100).endDueAuctions();

        ArgumentCaptor<LocalDateTime> nowCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(auctionRepository).findLiveDueForEnd(eq(AuctionStatus.LIVE), nowCaptor.capture(), any());
        assertThat(nowCaptor.getValue()).isEqualTo(LocalDateTime.now(FIXED_CLOCK));
    }

    @Test
    void 한_건이_실패해도_나머지_후보_처리를_계속한다() {
        when(auctionRepository.findLiveDueForEnd(eq(AuctionStatus.LIVE), any(), any()))
                .thenReturn(List.of(10L, 20L));
        org.mockito.Mockito.doThrow(new RuntimeException("settlement 실패"))
                .when(auctionEndService).endIfDue(10L);

        assertThatCode(
                () -> new AuctionEndScheduler(auctionRepository, auctionEndService, FIXED_CLOCK, true, 100)
                        .endDueAuctions()
        ).doesNotThrowAnyException();

        verify(auctionEndService).endIfDue(10L);
        verify(auctionEndService).endIfDue(20L);
    }

    @Test
    void 꺼져있으면_후보_조회조차_하지_않는다() {
        new AuctionEndScheduler(auctionRepository, auctionEndService, FIXED_CLOCK, false, 100).endDueAuctions();

        verify(auctionRepository, never()).findLiveDueForEnd(any(), any(), any());
        verify(auctionEndService, never()).endIfDue(any());
    }

    @Test
    void 후보가_없으면_아무것도_시도하지_않는다() {
        when(auctionRepository.findLiveDueForEnd(eq(AuctionStatus.LIVE), any(), any()))
                .thenReturn(List.of());

        new AuctionEndScheduler(auctionRepository, auctionEndService, FIXED_CLOCK, true, 100).endDueAuctions();

        verify(auctionEndService, times(0)).endIfDue(any());
    }

    @Test
    void 반복_polling은_매번_후보_전체를_그대로_service에_다시_넘긴다() {
        when(auctionRepository.findLiveDueForEnd(eq(AuctionStatus.LIVE), any(), any()))
                .thenReturn(List.of(10L));

        AuctionEndScheduler scheduler = new AuctionEndScheduler(auctionRepository, auctionEndService, FIXED_CLOCK, true, 100);
        scheduler.endDueAuctions();
        scheduler.endDueAuctions();

        verify(auctionEndService, times(2)).endIfDue(10L);
    }
}
