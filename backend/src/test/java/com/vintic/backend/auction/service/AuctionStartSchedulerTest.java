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

// #73-3: 후보 id 조회와 건당 처리(AuctionStartService)를 분리했으므로, 여기서는 "후보를 전부
// 시도하는지"/"batch size를 적용하는지"/"한 건 실패가 나머지를 막지 않는지"/"disable이면 아예
// 조회조차 안 하는지"만 검증한다 - 실제 상태 전이/AutoBid/Proxy 로직은 AuctionStartServiceTest가
// 담당한다(#73-1에서 이미 검증, 여기서 중복 검증하지 않는다).
@ExtendWith(MockitoExtension.class)
class AuctionStartSchedulerTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-08-18T22:00:00Z"), ZoneId.of("Asia/Seoul"));

    @Mock
    private AuctionRepository auctionRepository;

    @Mock
    private AuctionStartService auctionStartService;

    @Test
    void 후보로_조회된_모든_Auction을_시도한다() {
        when(auctionRepository.findScheduledDueForStart(eq(AuctionStatus.SCHEDULED), any(), any()))
                .thenReturn(List.of(1L, 2L, 3L));

        new AuctionStartScheduler(auctionRepository, auctionStartService, FIXED_CLOCK, true, 100).startDueAuctions();

        verify(auctionStartService).startIfDue(1L);
        verify(auctionStartService).startIfDue(2L);
        verify(auctionStartService).startIfDue(3L);
    }

    @Test
    void 설정된_batch_size가_Pageable에_그대로_적용된다() {
        when(auctionRepository.findScheduledDueForStart(eq(AuctionStatus.SCHEDULED), any(), any()))
                .thenReturn(List.of());

        new AuctionStartScheduler(auctionRepository, auctionStartService, FIXED_CLOCK, true, 25).startDueAuctions();

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(auctionRepository).findScheduledDueForStart(eq(AuctionStatus.SCHEDULED), any(), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(25);
    }

    @Test
    void now는_주입된_Clock_기준으로_조회한다() {
        when(auctionRepository.findScheduledDueForStart(eq(AuctionStatus.SCHEDULED), any(), any()))
                .thenReturn(List.of());

        new AuctionStartScheduler(auctionRepository, auctionStartService, FIXED_CLOCK, true, 100).startDueAuctions();

        ArgumentCaptor<LocalDateTime> nowCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(auctionRepository).findScheduledDueForStart(eq(AuctionStatus.SCHEDULED), nowCaptor.capture(), any());
        assertThat(nowCaptor.getValue()).isEqualTo(LocalDateTime.now(FIXED_CLOCK));
    }

    @Test
    void 한_건이_실패해도_나머지_후보_처리를_계속한다() {
        when(auctionRepository.findScheduledDueForStart(eq(AuctionStatus.SCHEDULED), any(), any()))
                .thenReturn(List.of(1L, 2L));
        org.mockito.Mockito.doThrow(new RuntimeException("락 대기 초과"))
                .when(auctionStartService).startIfDue(1L);

        assertThatCode(
                () -> new AuctionStartScheduler(auctionRepository, auctionStartService, FIXED_CLOCK, true, 100)
                        .startDueAuctions()
        ).doesNotThrowAnyException();

        verify(auctionStartService).startIfDue(1L);
        verify(auctionStartService).startIfDue(2L);
    }

    @Test
    void 꺼져있으면_후보_조회조차_하지_않는다() {
        new AuctionStartScheduler(auctionRepository, auctionStartService, FIXED_CLOCK, false, 100).startDueAuctions();

        verify(auctionRepository, never()).findScheduledDueForStart(any(), any(), any());
        verify(auctionStartService, never()).startIfDue(any());
    }

    @Test
    void 후보가_없으면_아무것도_시도하지_않는다() {
        when(auctionRepository.findScheduledDueForStart(eq(AuctionStatus.SCHEDULED), any(), any()))
                .thenReturn(List.of());

        new AuctionStartScheduler(auctionRepository, auctionStartService, FIXED_CLOCK, true, 100).startDueAuctions();

        verify(auctionStartService, times(0)).startIfDue(any());
    }

    // 반복 polling은 scheduler 스스로 중복을 걸러내지 않는다 - 매 polling마다 같은 후보가
    // 다시 조회되더라도 AuctionStartService.startIfDue()의 자체 재검증(#73-1: 이미 LIVE면 skip)에
    // 안전성을 전적으로 위임한다. 여기서는 그 위임 구조 자체(scheduler가 dedup을 하지 않는다는 것)만
    // 확인한다 - 실제 idempotent 동작은 AuctionStartServiceTest가 이미 검증했다.
    @Test
    void 반복_polling은_매번_후보_전체를_그대로_service에_다시_넘긴다() {
        when(auctionRepository.findScheduledDueForStart(eq(AuctionStatus.SCHEDULED), any(), any()))
                .thenReturn(List.of(1L));

        AuctionStartScheduler scheduler = new AuctionStartScheduler(auctionRepository, auctionStartService, FIXED_CLOCK, true, 100);
        scheduler.startDueAuctions();
        scheduler.startDueAuctions();

        verify(auctionStartService, times(2)).startIfDue(1L);
    }
}
