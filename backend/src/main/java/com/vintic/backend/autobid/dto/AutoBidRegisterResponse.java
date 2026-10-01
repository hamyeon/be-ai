package com.vintic.backend.autobid.dto;

import com.vintic.backend.autobid.domain.AutoBidSettingStatus;

import java.time.OffsetDateTime;

// bidOccurred/resultingBidAmount/isHighestBidder는 이제 ProxyPriceEngine의 실제 resolution 결과다
// (LIVE 등록에 한함 - SCHEDULED 등록은 여전히 false/null/false, Auction 가격에 영향이 없다).
// startsAt은 공통 시간 정책(TimePolicy, Asia/Seoul 고정)에 따라 OffsetDateTime으로 낸다.
public record AutoBidRegisterResponse(
        Long autoBidSettingId,
        Long auctionId,
        AutoBidSettingStatus status,
        Long maxAmount,
        Long currentPrice,
        Long minNextBidAmount,
        Long minCapAmount,
        OffsetDateTime startsAt,
        boolean bidOccurred,
        Long resultingBidAmount,
        boolean isHighestBidder
) {
}
