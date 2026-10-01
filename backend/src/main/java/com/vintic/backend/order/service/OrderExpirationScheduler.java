package com.vintic.backend.order.service;

import com.vintic.backend.order.domain.OrderStatus;
import com.vintic.backend.order.repository.OrderRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

// FINAL contract §12: "scheduler는 PAYMENT_PENDING만 처리한다." 후보 id 조회(non-locking)와
// 실제 전이(OrderExpirationService.expireIfDue(), 건당 별도 트랜잭션)를 분리한다 - 한 주문
// 처리 중 실패(락 대기 타임아웃 등)가 같은 회차의 다른 주문 처리를 막지 않게 하기 위함이다
// (AiCallLogCleaner와 동일하게 실패를 삼키고 다음 주기에 다시 시도한다 - 이 스케줄러가 멱등하기
// 때문에 안전하다).
@Component
@Slf4j
public class OrderExpirationScheduler {

    private final OrderRepository orderRepository;
    private final OrderExpirationService orderExpirationService;
    private final Clock clock;
    private final boolean enabled;

    public OrderExpirationScheduler(
            OrderRepository orderRepository,
            OrderExpirationService orderExpirationService,
            Clock clock,
            @Value("${payment.expiration.enabled:true}") boolean enabled
    ) {
        this.orderRepository = orderRepository;
        this.orderExpirationService = orderExpirationService;
        this.clock = clock;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${payment.expiration.cron:0 * * * * *}")
    public void expirePastDueOrders() {
        if (!enabled) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        List<Long> candidateIds = orderRepository.findExpiredPendingOrderIds(OrderStatus.PAYMENT_PENDING, now);
        for (Long orderId : candidateIds) {
            try {
                orderExpirationService.expireIfDue(orderId);
            } catch (RuntimeException e) {
                log.warn("Order 결제 기한 만료 처리에 실패했습니다. orderId={}, message={}", orderId, e.getMessage());
            }
        }
        if (!candidateIds.isEmpty()) {
            log.info("Order 결제 기한 만료 처리를 시도했습니다. count={}", candidateIds.size());
        }
    }
}
