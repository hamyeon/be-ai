package com.vintic.backend.autobid.dto;

import com.vintic.backend.autobid.domain.AutoBidSettingStatus;

import java.time.OffsetDateTime;

// canceledAt은 별도 컬럼을 추가하지 않고 AutoBidSetting.updatedAt을 그대로 쓴다 - cancel()이
// 호출될 때마다 updatedAt이 함께 갱신되므로, 그 순간의 updatedAt이 곧 취소 시각이다.
// 공통 시간 정책(TimePolicy, Asia/Seoul 고정)에 따라 OffsetDateTime으로 낸다.
public record AutoBidCancelResponse(
        Long autoBidSettingId,
        AutoBidSettingStatus status,
        OffsetDateTime canceledAt
) {
}
