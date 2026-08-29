package com.vintic.backend.auction.dto;

import com.vintic.backend.auction.domain.AuctionStatus;
import com.vintic.backend.auction.domain.CannotBidReason;
import com.vintic.backend.autobid.domain.AutoBidSettingStatus;

import java.time.OffsetDateTime;

// extensionCount/maxExtensions는 여전히 생략한다 - 종료 연장 정책(트리거 시점/연장 분/최대 횟수) 자체가
// 도메인 어디에도 없어 값을 지어내면 계약을 가짜로 통과시키는 것이 된다. docs/api/auction-api-contract-gap.md 참고.
// 시간 필드(bidRestrictedUntil/endsAt/serverTime)는 공통 시간 정책(TimePolicy, Asia/Seoul 고정)에 따라
// OffsetDateTime으로 +09:00 절대시각을 낸다 - 서비스가 Clock/TimePolicy로 변환해 채운다.
public record AuctionLiveResponse(
        Long auctionId,
        AuctionStatus status,
        Long currentPrice,
        Long minNextBidAmount,
        Long bidIncrement,
        String highestBidderMasked,
        boolean isMine,
        boolean canBid,
        CannotBidReason cannotBidReason,
        OffsetDateTime bidRestrictedUntil,
        OffsetDateTime endsAt,
        OffsetDateTime serverTime,
        AutoBidSettingStatus myAutoBidStatus,
        Long myCap,
        Long minCapAmount
) {
}
