package com.vintic.backend.autobid.dto;

import com.vintic.backend.autobid.domain.AutoBidSettingStatus;

// bidOccurred/resultingBidAmount/isHighestBidder는 #41 시점에는 Proxy engine이 없어
// 항상 false/null/false로 고정된다(임시 integration boundary, springdoc에 명시).
// CAP_REACHED에서 cap을 올려도 이 응답의 status는 CAP_REACHED로 유지된다 - 실제 가격 경쟁
// 결과 없이 ACTIVE 복귀를 임의로 확정하지 않는다(§13 policy).
public record AutoBidUpdateResponse(
        Long autoBidSettingId,
        AutoBidSettingStatus status,
        Long maxAmount,
        Long currentPrice,
        Long minCapAmount,
        boolean bidOccurred,
        Long resultingBidAmount,
        boolean isHighestBidder
) {
}
