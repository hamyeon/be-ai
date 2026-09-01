package com.vintic.backend.order;

import com.vintic.backend.common.exception.OrderAccessDeniedException;
import com.vintic.backend.common.exception.OrderCanceledException;
import com.vintic.backend.common.exception.OrderNotFoundException;
import com.vintic.backend.common.exception.PaymentExpiredException;
import com.vintic.backend.order.domain.OrderStatus;
import com.vintic.backend.order.dto.OrderPayResponse;
import com.vintic.backend.order.dto.OrderResponse;
import com.vintic.backend.order.service.OrderCommandService;
import com.vintic.backend.order.service.OrderQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// FINAL contract §12-13.
@WebMvcTest(OrderController.class)
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderQueryService orderQueryService;

    @MockitoBean
    private OrderCommandService orderCommandService;

    @Test
    void 조회_성공시_200과_FINAL_contract_필드를_반환한다() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        OrderResponse response = new OrderResponse(
                50L, 1L, OrderStatus.PAYMENT_PENDING,
                new OrderResponse.Product(10L, "아식스 노바블라스트 6 블랙", "Novablast 6", "https://example.com/a.jpg"),
                105000L, 3000L, 108000L,
                now.plusHours(2), now, null
        );
        when(orderQueryService.getOrder(50L, 2L)).thenReturn(response);

        mockMvc.perform(get("/api/orders/50").requestAttr("currentUserId", 2L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.orderId").value(50))
                .andExpect(jsonPath("$.data.auctionId").value(1))
                .andExpect(jsonPath("$.data.status").value("PAYMENT_PENDING"))
                .andExpect(jsonPath("$.data.product.productId").value(10))
                .andExpect(jsonPath("$.data.purchasePrice").value(105000))
                .andExpect(jsonPath("$.data.shippingFee").value(3000))
                .andExpect(jsonPath("$.data.totalAmount").value(108000))
                .andExpect(jsonPath("$.data.paidAt").doesNotExist());
    }

    @Test
    void 존재하지_않는_주문_조회는_404와_40402를_반환한다() throws Exception {
        when(orderQueryService.getOrder(999L, 2L))
                .thenThrow(new OrderNotFoundException("존재하지 않는 주문입니다. orderId: 999"));

        mockMvc.perform(get("/api/orders/999").requestAttr("currentUserId", 2L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value(40402));
    }

    @Test
    void 다른_사용자의_주문_조회는_403과_40304를_반환한다() throws Exception {
        when(orderQueryService.getOrder(50L, 3L))
                .thenThrow(new OrderAccessDeniedException("접근 권한이 없는 주문입니다. orderId: 50"));

        mockMvc.perform(get("/api/orders/50").requestAttr("currentUserId", 3L))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value(40304));
    }

    @Test
    void 결제_성공시_200과_PAID를_반환한다() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        when(orderCommandService.pay(50L, 2L)).thenReturn(new OrderPayResponse(50L, OrderStatus.PAID, now));

        mockMvc.perform(post("/api/orders/50/pay").requestAttr("currentUserId", 2L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.orderId").value(50))
                .andExpect(jsonPath("$.data.status").value("PAID"));
    }

    @Test
    void 존재하지_않는_주문_결제는_404와_40402를_반환한다() throws Exception {
        when(orderCommandService.pay(999L, 2L))
                .thenThrow(new OrderNotFoundException("존재하지 않는 주문입니다. orderId: 999"));

        mockMvc.perform(post("/api/orders/999/pay").requestAttr("currentUserId", 2L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value(40402));
    }

    @Test
    void 다른_사용자의_주문_결제는_403과_40304를_반환한다() throws Exception {
        when(orderCommandService.pay(50L, 3L))
                .thenThrow(new OrderAccessDeniedException("접근 권한이 없는 주문입니다. orderId: 50"));

        mockMvc.perform(post("/api/orders/50/pay").requestAttr("currentUserId", 3L))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value(40304));
    }

    @Test
    void 결제_기한이_만료된_주문의_결제는_409와_40910을_반환한다() throws Exception {
        when(orderCommandService.pay(50L, 2L))
                .thenThrow(new PaymentExpiredException("결제 기한이 만료되었습니다. orderId: 50"));

        mockMvc.perform(post("/api/orders/50/pay").requestAttr("currentUserId", 2L))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value(40910));
    }

    @Test
    void 취소된_주문의_결제는_409와_40915를_반환한다() throws Exception {
        when(orderCommandService.pay(50L, 2L))
                .thenThrow(new OrderCanceledException("취소된 주문입니다. orderId: 50"));

        mockMvc.perform(post("/api/orders/50/pay").requestAttr("currentUserId", 2L))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value(40915));
    }
}
