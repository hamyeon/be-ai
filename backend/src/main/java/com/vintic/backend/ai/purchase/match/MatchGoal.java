package com.vintic.backend.ai.purchase.match;

// Matcher에 넘기는 Goal의 조각. 설계안 6-2 요청의 goal 부분.
//
// PurchaseGoalCandidateRanker가 PurchaseGoal 엔티티에서 이 record를 만들어 넘긴다. 엔티티를 직접
// 넘기지 않고 따로 둔 것은 Matcher가 엔티티에 의존하지 않게 여기서 끊기 위해서다.
//
// minCondition·hardMaxAmount·sizeKr는 넘기지 않는다. 등급·예산·사이즈는 pre-filter가
// 구조화 필드로 끝내므로 Matcher는 "이 매물이 그 모델이 맞는가"와 자유 조건 충족만 본다.
public record MatchGoal(
        // 시세 카탈로그 키(nb990). null이면 모델 미지정 - 브랜드까지만 판정한다.
        String modelKey,
        // 사람이 읽는 모델명("New Balance 990"). 프롬프트와 reason 문구에 쓴다.
        String modelQuery,
        String brand,
        // "박스 있으면 좋음", "시카고 컬러" 등. null 가능. v1에서는 soft 점수에만 반영.
        String freeTextConditions
) {
}
