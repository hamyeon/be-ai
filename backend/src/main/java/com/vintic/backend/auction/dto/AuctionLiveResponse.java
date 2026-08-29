package com.vintic.backend.auction.dto;

import com.vintic.backend.auction.domain.AuctionStatus;
import com.vintic.backend.auction.domain.CannotBidReason;
import com.vintic.backend.autobid.domain.AutoBidSettingStatus;

import java.time.Instant;
import java.time.LocalDateTime;

// extensionCount/maxExtensions는 이번 #40에서 의도적으로 생략한다 - 종료 연장 정책(트리거 시점/연장 분/최대
// 횟수) 자체가 도메인 어디에도 없어 값을 지어내면 계약을 가짜로 통과시키는 것이 된다. docs/api/auction-api-contract-gap.md 참고.
// endsAt은 Auction.endAt(LocalDateTime)을 그대로 내려준다 - 저장된 시각의 timezone semantics가 프로젝트
// 전체에서 확정되어 있지 않아 임의로 offset을 붙이지 않는다(같은 문서에 gap으로 기록).
// serverTime은 응답 생성 시점에 새로 만드는 값이라 저장된 시각의 timezone 문제와 무관하다 - Instant로 절대시각을 낸다.
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
        LocalDateTime bidRestrictedUntil,
        LocalDateTime endsAt,
        Instant serverTime,
        AutoBidSettingStatus myAutoBidStatus,
        Long myCap,
        Long minCapAmount
) {
}
