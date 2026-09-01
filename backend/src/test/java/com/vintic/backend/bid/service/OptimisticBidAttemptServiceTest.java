package com.vintic.backend.bid.service;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.bid.dto.PlaceBidResponse;
import com.vintic.backend.common.exception.AuctionNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * #74-1 core unit test(§8) - Spring 컨텍스트/실제 트랜잭션 없이 {@link OptimisticBidAttemptService}
 * 가 (1) non-locking {@code findById()}를 쓰는지(=production의 {@code findByIdForUpdate()}와
 * 유일하게 다른 지점), (2) 로드한 Auction을 {@link BidCommandService}의 재사용 메서드에
 * 그대로 위임하는지만 확인한다. 실제 REQUIRES_NEW 트랜잭션 동작(새 물리 트랜잭션 여부)은
 * Spring 프록시가 필요해 #74-2 실제 MySQL IT에서 검증한다 - 여기서는 애너테이션 선언만
 * 회귀 확인한다.
 */
class OptimisticBidAttemptServiceTest {

    private final AuctionRepository auctionRepository = mock(AuctionRepository.class);
    private final BidCommandService bidCommandService = mock(BidCommandService.class);
    private final OptimisticBidAttemptService attemptService =
            new OptimisticBidAttemptService(auctionRepository, bidCommandService);

    @Test
    void findById로_non_locking_조회하고_그_Auction으로_기존_business_logic을_재사용한다() {
        Auction auction = mock(Auction.class);
        when(auctionRepository.findById(1L)).thenReturn(Optional.of(auction));
        PlaceBidResponse expected = new PlaceBidResponse(1L, 15000L, 15000L, 20000L, "홍*동", true, false, false, null, 0);
        when(bidCommandService.executeManualBidOnLoadedAuction(auction, 2L, 15000L, 99L)).thenReturn(expected);

        PlaceBidResponse actual = attemptService.attempt(1L, 2L, 15000L, 99L);

        assertThat(actual).isEqualTo(expected);
        verify(auctionRepository).findById(1L);
        verify(auctionRepository, never()).findByIdForUpdate(any());
        verify(bidCommandService).executeManualBidOnLoadedAuction(eq(auction), eq(2L), eq(15000L), eq(99L));
    }

    @Test
    void 존재하지_않는_Auction이면_AuctionNotFoundException을_던진다() {
        when(auctionRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> attemptService.attempt(404L, 2L, 15000L, 99L))
                .isInstanceOf(AuctionNotFoundException.class);
    }

    @Test
    void attempt_메서드는_REQUIRES_NEW_propagation을_선언한다() throws NoSuchMethodException {
        Method attempt = OptimisticBidAttemptService.class.getMethod(
                "attempt", Long.class, Long.class, Long.class, Long.class
        );
        Transactional annotation = attempt.getAnnotation(Transactional.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }
}
