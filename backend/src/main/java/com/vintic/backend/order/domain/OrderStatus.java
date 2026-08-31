package com.vintic.backend.order.domain;

// FINAL contract §12: PAYMENT_PENDING -> PAID | PAYMENT_EXPIRED | CANCELED만 허용되고
// 세 terminal 상태 사이의 전이는 없다. #56-1은 PAYMENT_PENDING 생성만 다룬다 -
// PAID(#56-2 pay)/PAYMENT_EXPIRED(#57 scheduler)/CANCELED(#56-2 forfeit)로의 전이는
// 이번 범위에 없지만, Order 스키마를 다시 손대지 않도록 enum 값은 미리 전부 선언해 둔다.
public enum OrderStatus {
    PAYMENT_PENDING,
    PAID,
    PAYMENT_EXPIRED,
    CANCELED
}
