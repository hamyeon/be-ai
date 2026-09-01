package com.vintic.backend.order;

import com.vintic.backend.common.dto.ApiResponse;
import com.vintic.backend.order.dto.OrderPayResponse;
import com.vintic.backend.order.dto.OrderResponse;
import com.vintic.backend.order.service.OrderCommandService;
import com.vintic.backend.order.service.OrderQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderQueryService orderQueryService;
    private final OrderCommandService orderCommandService;

    public OrderController(OrderQueryService orderQueryService, OrderCommandService orderCommandService) {
        this.orderQueryService = orderQueryService;
        this.orderCommandService = orderCommandService;
    }

    @Operation(
            summary = "주문 조회",
            description = "결제 예정 금액과 기한 표시용이다. 실제 PG 결제 API는 구현하지 않는다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "주문 소유자 아님(40304)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 주문(40402)")
    })
    @GetMapping("/{orderId}")
    public ResponseEntity<ApiResponse<OrderResponse>> getOrder(
            @PathVariable Long orderId,
            @RequestAttribute("currentUserId") Long userId
    ) {
        OrderResponse response = orderQueryService.getOrder(orderId, userId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(
            summary = "Mock 결제",
            description = "실제 PG 연동 없이 Order.status만 PAID로 전이시킨다. Auction.status는 변경하지 않는다. "
                    + "이미 PAID인 주문에 재호출해도 새로 처리하지 않고 기존 결과를 그대로 반환한다(상태 멱등, "
                    + "Idempotency-Key를 요구하지 않는다)."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "결제 성공 또는 이미 PAID인 주문의 재호출"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "주문 소유자 아님(40304)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 주문(40402)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "결제 기한 만료(40910) / 취소된 주문(40915)")
    })
    @PostMapping("/{orderId}/pay")
    public ResponseEntity<ApiResponse<OrderPayResponse>> pay(
            @PathVariable Long orderId,
            @RequestAttribute("currentUserId") Long userId
    ) {
        OrderPayResponse response = orderCommandService.pay(orderId, userId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
