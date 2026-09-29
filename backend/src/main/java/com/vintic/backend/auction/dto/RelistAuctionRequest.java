package com.vintic.backend.auction.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.OffsetDateTime;

// 재경매(기존 상품에 대한 두 번째 경매 등록) 전용 요청이다. bidIncrement는 받지 않는다 -
// 입찰 단위는 판매자가 설정하지 않고 서버가 고정값(BidIncrementPolicy.DEFAULT_BID_INCREMENT)을
// 적용한다.
public record RelistAuctionRequest(

        @NotNull(message = "시작가는 필수입니다.")
        @Positive(message = "시작가는 0보다 커야 합니다.")
        Long startPrice,

        @NotNull(message = "시작 시각은 필수입니다.")
        OffsetDateTime startAt,

        @NotNull(message = "종료 시각은 필수입니다.")
        OffsetDateTime endAt
) {
}
