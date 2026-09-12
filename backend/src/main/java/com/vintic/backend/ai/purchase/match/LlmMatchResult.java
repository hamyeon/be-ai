package com.vintic.backend.ai.purchase.match;

// LLM이 listing-match 스키마대로 돌려준 원본. MatchResultValidator를 거쳐야 MatchResult가 된다.
record LlmMatchResult(
        boolean matched,
        double semanticScore,
        String reason
) {
}
