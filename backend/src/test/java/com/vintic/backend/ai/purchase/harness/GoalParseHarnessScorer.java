package com.vintic.backend.ai.purchase.harness;

import com.vintic.backend.ai.purchase.dto.GoalDraft;

import java.util.List;
import java.util.Objects;

// 케이스 하나를 채점한다. 필드별 정확 일치 + 자유 조건 키워드 재현율.
final class GoalParseHarnessScorer {

    record CaseScore(
            String id,
            boolean brandOk,
            boolean modelKeyOk,
            boolean conditionOk,
            boolean amountOk,
            boolean sizeOk,
            // 키워드가 없는 케이스는 NaN - 평균에서 뺀다.
            double keywordRecall,
            double confidence,
            long latencyMs,
            String failure,
            GoalDraft draft,
            GoalParseHarnessCase harnessCase
    ) {

        boolean allFieldsOk() {
            return brandOk && modelKeyOk && conditionOk && amountOk && sizeOk;
        }

        boolean failed() {
            return failure != null;
        }

        static CaseScore failed(GoalParseHarnessCase harnessCase, long latencyMs, RuntimeException e) {
            return new CaseScore(harnessCase.id(), false, false, false, false, false, Double.NaN,
                    0.0, latencyMs, e.getClass().getSimpleName() + ": " + e.getMessage(), null, harnessCase);
        }
    }

    private GoalParseHarnessScorer() {
    }

    static CaseScore score(GoalParseHarnessCase harnessCase, GoalDraft draft, long latencyMs) {
        GoalParseHarnessCase.Expected expected = harnessCase.expected();
        String actualCondition = draft.minCondition() == null ? null : draft.minCondition().name();

        return new CaseScore(
                harnessCase.id(),
                Objects.equals(expected.brand(), draft.brand()),
                Objects.equals(expected.modelKey(), draft.modelKey()),
                Objects.equals(expected.minCondition(), actualCondition),
                Objects.equals(expected.hardMaxAmount(), draft.hardMaxAmount()),
                Objects.equals(expected.sizeKr(), draft.sizeKr()),
                keywordRecall(expected.freeTextKeywords(), draft.freeTextConditions()),
                draft.confidence(),
                latencyMs,
                null,
                draft,
                harnessCase
        );
    }

    private static double keywordRecall(List<String> keywords, String freeText) {
        if (keywords == null || keywords.isEmpty()) {
            return Double.NaN;
        }
        if (freeText == null) {
            return 0.0;
        }
        String haystack = freeText.toLowerCase();
        long hits = keywords.stream().filter(keyword -> haystack.contains(keyword.toLowerCase())).count();
        return (double) hits / keywords.size();
    }
}
