package com.vintic.backend.auction.service;

import com.vintic.backend.common.exception.InvalidAuctionTimeException;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;

// 경매 시작/종료 시각 검증의 단일 지점 - 최초 등록(ProductRegistrationService)과 재경매
// 등록(AuctionRelistService)이 이 규칙을 공유한다. 절대 시각(instant) 기준으로 비교하므로
// startAt/endAt이 서로 다른 UTC 오프셋으로 와도 실제 경과 시간을 정확히 반영한다.
public final class AuctionSchedulePolicy {

    public static final Duration MIN_DURATION = Duration.ofHours(1);

    private AuctionSchedulePolicy() {
    }

    public static void validate(OffsetDateTime startAt, OffsetDateTime endAt, Clock clock) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (!startAt.isAfter(now)) {
            throw new InvalidAuctionTimeException("시작 시각은 현재 이후여야 합니다.");
        }
        if (Duration.between(startAt, endAt).compareTo(MIN_DURATION) < 0) {
            throw new InvalidAuctionTimeException("경매 진행 시간은 최소 1시간이어야 합니다.");
        }
    }
}
