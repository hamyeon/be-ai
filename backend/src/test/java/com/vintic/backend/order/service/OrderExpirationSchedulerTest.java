package com.vintic.backend.order.service;

import com.vintic.backend.order.domain.OrderStatus;
import com.vintic.backend.order.repository.OrderRepository;
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

// AiCallLogCleanerTest와 동일한 구조 - 후보 id 조회와 건당 처리(OrderExpirationService)를
// 분리했으므로 여기서는 "후보를 전부 시도하는지"와 "한 건 실패가 나머지를 막지 않는지"만 검증한다.
// 실제 상태 전이/penalty/BackupOffer 로직은 OrderExpirationServiceTest가 담당한다.
@ExtendWith(MockitoExtension.class)
class OrderExpirationSchedulerTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-08-18T22:00:00Z"), ZoneId.of("Asia/Seoul"));

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderExpirationService orderExpirationService;

    @Test
    void 후보로_조회된_모든_Order를_시도한다() {
        when(orderRepository.findExpiredPendingOrderIds(eq(OrderStatus.PAYMENT_PENDING), any()))
                .thenReturn(List.of(1L, 2L, 3L));

        new OrderExpirationScheduler(orderRepository, orderExpirationService, FIXED_CLOCK, true).expirePastDueOrders();

        verify(orderExpirationService).expireIfDue(1L);
        verify(orderExpirationService).expireIfDue(2L);
        verify(orderExpirationService).expireIfDue(3L);
    }

    @Test
    void 한_건이_실패해도_나머지_후보_처리를_계속한다() {
        when(orderRepository.findExpiredPendingOrderIds(eq(OrderStatus.PAYMENT_PENDING), any()))
                .thenReturn(List.of(1L, 2L));
        org.mockito.Mockito.doThrow(new RuntimeException("락 대기 초과"))
                .when(orderExpirationService).expireIfDue(1L);

        assertThatCode(
                () -> new OrderExpirationScheduler(orderRepository, orderExpirationService, FIXED_CLOCK, true)
                        .expirePastDueOrders()
        ).doesNotThrowAnyException();

        verify(orderExpirationService).expireIfDue(1L);
        verify(orderExpirationService).expireIfDue(2L);
    }

    @Test
    void 꺼져있으면_후보_조회조차_하지_않는다() {
        new OrderExpirationScheduler(orderRepository, orderExpirationService, FIXED_CLOCK, false).expirePastDueOrders();

        verify(orderRepository, never()).findExpiredPendingOrderIds(any(), any());
        verify(orderExpirationService, never()).expireIfDue(any());
    }

    @Test
    void 후보가_없으면_아무것도_시도하지_않는다() {
        when(orderRepository.findExpiredPendingOrderIds(eq(OrderStatus.PAYMENT_PENDING), any()))
                .thenReturn(List.of());

        new OrderExpirationScheduler(orderRepository, orderExpirationService, FIXED_CLOCK, true).expirePastDueOrders();

        verify(orderExpirationService, times(0)).expireIfDue(any());
    }
}
