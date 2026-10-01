package com.vintic.backend.autobid.dto;

// aiRecommendedCap은 이번 #40에서 항상 minCapAmount fallback이다 - buyer용 AutoBid 상한가 추천 소스가
// 도메인에 없다(Product.recommendedPrice는 seller용 판매가 추천이라 의미가 달라 재사용하지 않는다).
// docs/auction-api-spec-final.md §4 참고.
public record AutoBidRecommendationResponse(
        Long auctionId,
        Long aiRecommendedCap,
        Long currentPrice,
        Long minCapAmount,
        Long bidIncrement
) {
}
