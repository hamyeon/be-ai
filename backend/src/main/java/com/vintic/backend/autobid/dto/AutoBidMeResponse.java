package com.vintic.backend.autobid.dto;

import com.vintic.backend.autobid.domain.AutoBidSettingStatus;

import java.time.OffsetDateTime;

// 이 응답은 항상 "현재 설정"(RESERVED/ACTIVE/CAP_REACHED)만 담는다 - CANCELED는 조회 쿼리
// 단계에서 이미 제외되므로 status가 CANCELED로 내려올 일이 없다.
// canModify/canCancel은 현재 규칙상 세 상태 모두 true다(§9 최소 규칙) - 향후 상태별로
// 달라질 수 있는 지점을 남겨두기 위해 상수 대신 필드로 유지한다.
// startsAt/serverTime은 공통 시간 정책(TimePolicy, Asia/Seoul 고정)에 따라 OffsetDateTime으로 낸다.
// purchaseGoalId/managedByPurchaseAgent(Day2-B): purchaseGoalId는 이 AutoBid를 만든 Goal의
// FK를 그대로 보여주는 이력값이다(Goal이 끝나도 null이 되지 않는다). managedByPurchaseAgent는
// "지금 이 순간" Agent가 실제로 관리 중인지(ENGAGED/CANCEL_REQUESTED + 이 경매가 currentAuctionId)를
// 매 조회마다 다시 계산한 값이다 - 이 둘이 다를 수 있다(과거에 Agent가 만들었지만 Goal이
// 끝나 더 이상 보호되지 않는 경우).
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
        boolean canCancel,
        Long purchaseGoalId,
        boolean managedByPurchaseAgent
) {
}
