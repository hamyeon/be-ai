package com.vintic.backend.recommendation.dto;

import java.util.List;

// 추천 결과.
//
// personalized가 false면 취향 데이터가 부족해 Fallback(마감 임박 + 인기)으로 채운 결과다.
// 프론트가 "회원님을 위한 추천" 대신 "지금 인기 있는 경매" 같은 문구를 쓸 수 있도록
// 이 사실을 숨기지 않고 내려준다.
public record RecommendationResponse(
        boolean personalized,
        String reason,
        List<RecommendedAuction> items
) {

    public record RecommendedAuction(
            Long auctionId,
            Long productId,
            String brand,
            String model,
            String colorway,
            Integer sizeKr,
            Long currentPrice,
            String endAt,
            // 취향 벡터와의 코사인 유사도. Fallback일 때는 null이다.
            Double similarity
    ) {
    }
}
