package com.vintic.backend.autobid.dto;

import com.vintic.backend.autobid.domain.AutoBidSettingStatus;

import java.time.OffsetDateTime;

// 이 응답은 항상 "현재 설정"(RESERVED/ACTIVE/CAP_REACHED)만 담는다 - CANCELED는 조회 쿼리
// 단계에서 이미 제외되므로 status가 CANCELED로 내려올 일이 없다.
// canModify/canCancel은 현재 규칙상 세 상태 모두 true다(§9 최소 규칙) - 향후 상태별로
// 달라질 수 있는 지점을 남겨두기 위해 상수 대신 필드로 유지한다.
// startsAt/serverTime은 공통 시간 정책(TimePolicy, Asia/Seoul 고정)에 따라 OffsetDateTime으로 낸다.
public record AutoBidMeResponse(
        Long autoBidSettingId,
        Long auctionId,
        AutoBidSettingStatus status,
        Long maxAmount,
        Long currentPrice,
        Long minCapAmount,
        OffsetDateTime startsAt,
        OffsetDateTime serverTime,
        boolean canModify,
        boolean canCancel
) {
}
