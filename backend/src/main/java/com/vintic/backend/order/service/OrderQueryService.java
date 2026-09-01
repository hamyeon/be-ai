package com.vintic.backend.order.service;

import com.vintic.backend.common.exception.OrderAccessDeniedException;
import com.vintic.backend.common.exception.OrderNotFoundException;
import com.vintic.backend.common.util.ProductDisplayName;
import com.vintic.backend.common.util.TimePolicy;
import com.vintic.backend.order.domain.Order;
import com.vintic.backend.order.dto.OrderResponse;
import com.vintic.backend.order.repository.OrderRepository;
import com.vintic.backend.product.domain.Product;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

// FINAL contract §12. Order는 별도 persisted result가 아니라 이 서비스가 조회 시점의 status를
// 그대로 반환한다(side-effect 없음, AuctionResultQueryService/BackupOfferQueryService와 동일
// 원칙) - PAYMENT_EXPIRED 전이 자체는 scheduler(#57-2)의 책임이라 이 서비스는 lazy 판정도 하지
// 않는다(§12가 GET에 그런 계산을 요구하지 않는다, 저장된 status를 그대로 보여준다).
@Service
public class OrderQueryService {

    private final OrderRepository orderRepository;
    private final Clock clock;

    public OrderQueryService(OrderRepository orderRepository, Clock clock) {
        this.orderRepository = orderRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrder(Long orderId, Long userId) {
        Order order = orderRepository.findByIdWithAuctionAndProduct(orderId)
                .orElseThrow(() -> new OrderNotFoundException("존재하지 않는 주문입니다. orderId: " + orderId));

        if (!order.getBuyer().getId().equals(userId)) {
            throw new OrderAccessDeniedException("접근 권한이 없는 주문입니다. orderId: " + orderId);
        }

        Product product = order.getAuction().getProduct();

        return new OrderResponse(
                order.getId(),
                order.getAuction().getId(),
                order.getStatus(),
                new OrderResponse.Product(
                        product.getId(),
                        ProductDisplayName.name(product),
                        ProductDisplayName.subName(product),
                        product.getImageUrls().isEmpty() ? null : product.getImageUrls().get(0)
                ),
                order.getPurchasePrice(),
                order.getShippingFee(),
                order.getTotalAmount(),
                TimePolicy.toApiTime(order.getPaymentDeadline()),
                TimePolicy.toApiTime(LocalDateTime.now(clock)),
                TimePolicy.toApiTime(order.getPaidAt())
        );
    }
}
