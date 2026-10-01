package com.vintic.backend.ai.purchase.parser;

// LLM이 goal-parse 스키마대로 돌려준 원본. GoalDraftValidator를 거쳐야 GoalDraft가 된다.
//
// 스키마(goal-parse-v1.schema.json)와 필드가 1:1이다. 스키마를 바꾸면 여기도 같이 바꾼다.
record LlmGoalDraft(
        String modelKey,
        String modelQuery,
        String brand,
        String minCondition,
        Long hardMaxAmount,
        Integer sizeKr,
        String freeTextConditions,
        double confidence
) {
}
