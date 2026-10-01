package com.vintic.backend.bid.service;

import com.vintic.backend.bid.dto.PlaceBidResponse;

/**
 * #74 실험 전용 instrumentation(§7): logical request 하나(재시도 포함 전체)의 관찰값이다.
 * production 응답 contract({@link PlaceBidResponse})에는 필드를 추가하지 않고, 이 별도
 * wrapper로만 harness가 conflictCount/attemptsUsed를 집계한다.
 */
public record OptimisticBidOutcome(
        PlaceBidResponse response,
        int attemptsUsed,
        int conflictCount,
        boolean exhausted
) {
}
