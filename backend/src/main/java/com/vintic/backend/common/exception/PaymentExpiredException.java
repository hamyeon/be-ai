package com.vintic.backend.common.exception;

// FINAL contract §11: forfeit 대상 Order가 이미 PAYMENT_EXPIRED로 전이된 뒤 forfeit을 시도하는
// 경우. #56-2 시점엔 Order를 PAYMENT_EXPIRED로 전이시키는 scheduler(#57)가 아직 없어 production
// 경로로는 도달 불가능하다 - 계약이 명시한 실패 표면(§11 "발생 가능: 40910")을 미리 코드화해
// #57에서 scheduler가 붙어도 이 분기를 다시 만들 필요가 없게 한다.
public class PaymentExpiredException extends RuntimeException {
    public PaymentExpiredException(String message) {
        super(message);
    }
}
