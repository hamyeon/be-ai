package com.vintic.backend.ai.purchase.match;

// Matcher 판정 결과. 설계안 6-2 응답.
//
// matched: 매물이 Goal의 모델(modelKey)이 맞는가. hard filter - false면 후보 제외.
// semanticScore: 자유 조건(freeTextConditions) 충족 정도를 섞은 soft 점수(0~1).
//                ranking 3순위 tie-break에만 쓰인다. matched=false면 의미 없다.
// reason: 사람이 읽는 근거. Goal 상세의 평가 이력에 그대로 보여준다.
// listingModelKey: 규칙이 매물에서 읽어낸 카탈로그 키. LLM과 규칙이 갈릴 때 원인을 볼 수 있게 남긴다.
public record MatchResult(
        boolean matched,
        double semanticScore,
        String reason,
        String listingModelKey
) {

    public MatchResult {
        semanticScore = Math.max(0.0, Math.min(1.0, semanticScore));
    }

    public static MatchResult rejected(String reason, String listingModelKey) {
        return new MatchResult(false, 0.0, reason, listingModelKey);
    }
}
