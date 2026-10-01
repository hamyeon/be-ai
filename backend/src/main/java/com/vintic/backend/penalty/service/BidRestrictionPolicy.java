package com.vintic.backend.penalty.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

// FINAL contract §14: "회차별 제재 기간 정책은 서버 설정으로 관리하며 이 계약에 고정하지 않는다."
// 사용자 확정(#57-2): escalating(회차별 증가) 없이 고정 기간만 적용한다 - 코드에 하드코딩하지
// 않고 설정값으로 외부화한다(AiCallLogCleaner의 @Value 패턴 재사용). FORFEITED는 이 정책을 타지
// 않는다 - 호출자(OrderExpirationService)가 PAYMENT_EXPIRED penalty에서만 사용한다.
@Component
public class BidRestrictionPolicy {

    private final long restrictionDays;

    public BidRestrictionPolicy(@Value("${penalty.bid-restriction-days:7}") long restrictionDays) {
        this.restrictionDays = restrictionDays;
    }

    public LocalDateTime restrictedUntil(LocalDateTime now) {
        return now.plusDays(restrictionDays);
    }
}
