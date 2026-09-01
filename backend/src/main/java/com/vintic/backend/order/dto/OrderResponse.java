package com.vintic.backend.order.dto;

import com.vintic.backend.order.domain.OrderStatus;

import java.time.OffsetDateTime;

// FINAL contract §12 전체 shape. paidAt은 계약상 optional(X) - 미결제면 null이다.
public record OrderResponse(
        Long orderId,
        Long auctionId,
        OrderStatus status,
        Product product,
        Long purchasePrice,
        Long shippingFee,
        Long totalAmount,
        OffsetDateTime paymentDeadline,
        OffsetDateTime serverTime,
        OffsetDateTime paidAt
) {
    public record Product(
            Long productId,
            String name,
            String subName,
            String imageUrl
    ) {
    }
}
