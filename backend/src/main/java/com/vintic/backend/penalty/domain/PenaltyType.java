package com.vintic.backend.penalty.domain;

// FINAL contract §14. PAYMENT_EXPIRED는 #57(결제 기한 만료 scheduler)에서 실제로 기록되기
// 시작한다 - #56-2엔 그 scheduler가 없어 FORFEITED만 생성된다(Penalty.forfeited() 참고).
public enum PenaltyType {
    FORFEITED,
    PAYMENT_EXPIRED
}
