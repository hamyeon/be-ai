package com.vintic.backend.common.exception;

// FINAL contract §13: 낙찰 포기(forfeit)로 CANCELED된 주문에 결제를 시도하는 경우.
// PaymentExpiredException(40910)과 마찬가지로 상태 전이 자체를 막는 InvalidOrderStatusException과는
// 다른 계층이다 - 이건 계약이 정의한 실패 표면(409/40915)이고, InvalidOrderStatusException은
// 서비스가 먼저 걸렀어야 하는 상태에서 도메인 메서드가 호출된 프로그래밍 오류 가드다.
public class OrderCanceledException extends RuntimeException {
    public OrderCanceledException(String message) {
        super(message);
    }
}
