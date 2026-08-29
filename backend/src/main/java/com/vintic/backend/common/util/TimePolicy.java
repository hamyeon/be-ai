package com.vintic.backend.common.util;

import com.vintic.backend.config.ClockConfig;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

// API 응답의 시간 필드를 FINAL contract(ISO-8601 absolute timestamp, 예: 2026-08-17T20:00:00+09:00)로
// 통일하는 단일 지점이다. 저장된 LocalDateTime(Auction.startAt/endAt, AutoBidSetting.updatedAt 등)은
// Asia/Seoul 벽시계 값이라는 전제로 ClockConfig.APP_ZONE을 적용해 OffsetDateTime으로 변환한다.
// DB 컬럼 타입 자체는 바꾸지 않는다 - 변환은 API boundary(서비스가 응답 DTO를 만드는 지점)에서만 한다.
public final class TimePolicy {

    private TimePolicy() {
    }

    public static OffsetDateTime toApiTime(LocalDateTime localDateTime) {
        if (localDateTime == null) {
            return null;
        }
        return localDateTime.atZone(ClockConfig.APP_ZONE).toOffsetDateTime();
    }
}
