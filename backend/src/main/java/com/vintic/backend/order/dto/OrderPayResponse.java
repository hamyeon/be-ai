package com.vintic.backend.order.dto;

import com.vintic.backend.order.domain.OrderStatus;

import java.time.OffsetDateTime;

// FINAL contract §13. status는 항상 PAID 고정.
public record OrderPayResponse(
        Long orderId,
        OrderStatus status,
        OffsetDateTime paidAt
) {
}
