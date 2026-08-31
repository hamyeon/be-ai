package com.vintic.backend.auction.dto;

import com.vintic.backend.auction.domain.AuctionStatus;

import java.time.OffsetDateTime;
import java.util.List;

// 컨트롤러 슬라이스 테스트들이 AuctionDetailResponse의 깊은 중첩 구조를 매번 새로 조립하지
// 않도록 돕는 최소 fixture다. 인증/라우팅처럼 응답 내용 자체를 검증하지 않는 테스트 전용이며,
// 실제 필드 값 검증(AuctionControllerTest)은 이 fixture에 의존하지 않고 직접 값을 채운다.
public final class AuctionDetailFixtures {

    private AuctionDetailFixtures() {
    }

    public static AuctionDetailResponse sample() {
        OffsetDateTime now = OffsetDateTime.now();
        return new AuctionDetailResponse(
                1L,
                AuctionStatus.LIVE,
                new AuctionDetailResponse.Product(10L, "Nike Dunk Low Panda", "Nike", "Dunk Low", "B", List.of("https://example.com/a.jpg")),
                new AuctionDetailResponse.Seller(100L, "seller", null, 0),
                "설명",
                10000L,
                10000L,
                5000L,
                15000L,
                15000L,
                now,
                now.plusHours(1),
                now,
                null,
                15000L,
                null,
                0,
                false,
                0,
                new AuctionDetailResponse.MyState(false, false, false, null, null, null, null),
                null
        );
    }
}
