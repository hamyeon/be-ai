package com.vintic.backend.order.service;

import com.vintic.backend.common.exception.OrderAccessDeniedException;
import com.vintic.backend.common.exception.OrderCanceledException;
import com.vintic.backend.common.exception.OrderNotFoundException;
import com.vintic.backend.common.exception.PaymentExpiredException;
import com.vintic.backend.common.util.TimePolicy;
import com.vintic.backend.order.domain.Order;
import com.vintic.backend.order.dto.OrderPayResponse;
import com.vintic.backend.order.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

// FINAL contract §13. 실제 PG 연동 없는 Mock 결제 - Order.status만 바꾸고 Auction.status는
// 건드리지 않는다. Idempotency-Key를 요구하지 않는다(§0.11에 이 endpoint가 없다) - 엔드포인트
// 자체가 상태 멱등이어야 한다(PAID 재호출은 새 에러 없이 기존 결과를 그대로 반환).
@Service
public class OrderCommandService {

    private final OrderRepository orderRepository;
    private final Clock clock;

    public OrderCommandService(OrderRepository orderRepository, Clock clock) {
        this.orderRepository = orderRepository;
        this.clock = clock;
    }

    @Transactional
    public OrderPayResponse pay(Long orderId, Long userId) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new OrderNotFoundException("존재하지 않는 주문입니다. orderId: " + orderId));

        if (!order.getBuyer().getId().equals(userId)) {
            throw new OrderAccessDeniedException("접근 권한이 없는 주문입니다. orderId: " + orderId);
        }

        switch (order.getStatus()) {
            case PAID -> {
                // 상태 멱등 - 새로 처리하지 않고 기존 결과를 그대로 반환한다(§13).
                return toResponse(order);
            }
            case PAYMENT_EXPIRED -> throw new PaymentExpiredException(
                    "결제 기한이 만료되었습니다. orderId: " + order.getId()
            );
            case CANCELED -> throw new OrderCanceledException(
                    "취소된 주문입니다. orderId: " + order.getId()
            );
            case PAYMENT_PENDING -> {
                // 아래에서 실제로 처리한다.
            }
        }

        order.pay(LocalDateTime.now(clock));

        return toResponse(order);
    }

    private OrderPayResponse toResponse(Order order) {
        return new OrderPayResponse(order.getId(), order.getStatus(), TimePolicy.toApiTime(order.getPaidAt()));
    }
}
