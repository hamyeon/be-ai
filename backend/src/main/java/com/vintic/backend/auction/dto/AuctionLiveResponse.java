package com.vintic.backend.auction.dto;

import com.vintic.backend.auction.domain.AuctionStatus;
import com.vintic.backend.auction.domain.CannotBidReason;
import com.vintic.backend.autobid.domain.AutoBidSettingStatus;

import java.time.OffsetDateTime;

// extensionCount/maxExtensions(FINAL contract §0.13/§9)는 Auction.extensionCount/MAX_EXTENSIONS를
// 그대로 반영한다. 시간 필드(bidRestrictedUntil/endsAt/serverTime)는 공통 시간 정책(TimePolicy,
// Asia/Seoul 고정)에 따라 OffsetDateTime으로 +09:00 절대시각을 낸다 - 서비스가 Clock/TimePolicy로
// 변환해 채운다.
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
        int extensionCount,
        int maxExtensions,
        AutoBidSettingStatus myAutoBidStatus,
        Long myCap,
        Long minCapAmount
) {
}
