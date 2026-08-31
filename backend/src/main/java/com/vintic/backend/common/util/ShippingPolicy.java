package com.vintic.backend.common.util;

// FINAL contract 모든 응답 예시(§10/§12/§15)가 shippingFee=3000으로 고정돼 있고, Product/Auction
// 어디에도 배송비 필드가 없다 - 사용자 확인 결과 v1 범위에서는 전역 고정 상수로 처리한다(#56-1
// 확정). Order(정산 시점에 값을 얼려 저장)와 BackupOffer(조회 시점에 즉석 계산, 아래 참고) 양쪽이
// 이 값을 공유한다 - 상품별 배송비가 필요해지면 그때 별도 이슈로 스키마를 바꾼다.
public final class ShippingPolicy {

    public static final long FLAT_FEE = 3000L;

    private ShippingPolicy() {
    }
}
