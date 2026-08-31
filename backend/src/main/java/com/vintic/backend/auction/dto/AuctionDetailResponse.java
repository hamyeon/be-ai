package com.vintic.backend.auction.dto;

import com.vintic.backend.auction.domain.AuctionStatus;
import com.vintic.backend.auction.domain.CannotBidReason;
import com.vintic.backend.autobid.domain.AutoBidSettingStatus;

import java.time.OffsetDateTime;
import java.util.List;

// FINAL contract §1 shape. #55 gap: product.subName은 전용 domain source가 없어
// ProductDisplayName으로 최선 근사치를 채운다. seller.completedSalesCount는 Order 도메인
// 자체가 없어(DEFERRED DATA SOURCE GAP, 아래 Seller record 주석 참고 - #56에서 연결 예정)
// shape만 충족하고 semantics는 아직 없다. 나머지 필드는 모두 기존 Auction/Product/User/
// AutoBidSetting/AuctionLike source of truth를 그대로 반영한다.
public record AuctionDetailResponse(
        Long auctionId,
        AuctionStatus status,
        Product product,
        Seller seller,
        String description,
        Long startPrice,
        Long currentPrice,
        Long bidIncrement,
        Long minNextBidAmount,
        Long minCapAmount,
        OffsetDateTime startsAt,
        OffsetDateTime endsAt,
        OffsetDateTime serverTime,
        Long aiEstimatedPrice,
        Long aiRecommendedAutoBidCap,
        String aiPriceReason,
        int bidCount,
        boolean isLiked,
        int likeCount,
        MyState myState,
        Long finalPrice
) {
    public record Product(
            Long productId,
            String name,
            String brand,
            String subName,
            String grade,
            List<String> imageUrls
    ) {
    }

    // completedSalesCount: FINAL contract §1은 Int, Required(O) - non-null이다. #55
    // DEFERRED DATA SOURCE GAP: Order 도메인이 아직 없어(§api/auction-api-contract-gap.md
    // Not Implemented Yet #12/#13, #56에서 구현 예정) 실제 판매 완료 건수를 집계할 source가
    // 없다. AuctionQueryService가 항상 0을 채우는 것은 "실제로 0건"이라는 의미가 아니다 -
    // non-null 계약을 어기지 않기 위한 shape-only placeholder다. Order 도메인이 연결되기
    // 전까지 이 필드의 값에 실제 의미(누적 판매 건수)를 부여하지 않는다.
    public record Seller(
            Long sellerId,
            String nickname,
            String profileImageUrl,
            int completedSalesCount
    ) {
    }

    public record MyState(
            boolean isSeller,
            boolean isHighestBidder,
            boolean canBid,
            CannotBidReason cannotBidReason,
            OffsetDateTime bidRestrictedUntil,
            AutoBidSettingStatus autoBidStatus,
            Long autoBidCap
    ) {
    }
}
