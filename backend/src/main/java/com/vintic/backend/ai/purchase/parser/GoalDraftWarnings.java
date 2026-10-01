package com.vintic.backend.ai.purchase.parser;

import com.vintic.backend.ai.purchase.dto.GoalDraft;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

// 확인 화면에 띄울 안내문. 규칙 파서와 LLM 파서가 같은 문구를 쓰도록 한곳에 둔다.
//
// 경고는 "AI가 못 알아봤다"만이 아니라 "이 값은 v1에서 이렇게 취급된다"도 포함한다.
// 사용자가 "박스 없으면 안 돼"라고 써도 v1에서는 참고 사항이라는 걸 등록 전에 알아야
// 낙찰 뒤에 항의하지 않는다(설계안 6-1).
final class GoalDraftWarnings {

    static final String MODEL_UNKNOWN = "모델을 알아보지 못했습니다. 모델을 직접 선택해 주세요.";
    static final String MODEL_NOT_IN_CATALOG = "시세 정보가 없는 모델입니다. v1 구매 Agent는 시세가 있는 모델만 탐색합니다.";
    static final String BRAND_ONLY = "브랜드만 인식했습니다. 모델을 지정하지 않으면 해당 브랜드 전체가 후보가 됩니다.";
    static final String SIZE_MISSING = "사이즈가 없습니다. 사이즈를 지정하지 않으면 모든 사이즈가 후보가 됩니다.";
    static final String BUDGET_MISSING = "예산 상한이 없습니다. 상한을 정하지 않으면 Agent가 참여할 수 없습니다.";
    static final String BUDGET_MIN_IGNORED = "최소 금액 조건은 지원하지 않아 무시했습니다.";
    static final String BUDGET_OUT_OF_RANGE = "예산 금액이 비정상 범위라 비웠습니다. 직접 입력해 주세요.";
    static final String CONDITION_MISSING = "상태 등급 조건이 없습니다. 모든 등급이 후보가 됩니다.";
    static final String FREE_TEXT_IS_SOFT = "'%s' 조건은 v1에서 필수가 아닌 참고 사항으로만 반영됩니다.";
    static final String AI_FALLBACK = "AI 해석에 실패해 규칙 기반 초안을 보여드립니다. 내용을 꼭 확인해 주세요.";

    // "박스 필수", "꼭 정품", "무조건 풀박" - 사용자가 hard 조건으로 쓴 표현.
    private static final Pattern HARD_INTENT = Pattern.compile("(필수|꼭|무조건|반드시|아니면\\s*안|없으면\\s*안)");

    private GoalDraftWarnings() {
    }

    static List<String> forDraft(String modelKey, String brand, Long hardMaxAmount, Integer sizeKr,
                                 String freeText, boolean minPriceIgnored, boolean budgetOutOfRange) {
        List<String> warnings = new ArrayList<>();
        if (modelKey == null) {
            warnings.add(brand == null ? MODEL_UNKNOWN : BRAND_ONLY);
        }
        if (hardMaxAmount == null) {
            warnings.add(budgetOutOfRange ? BUDGET_OUT_OF_RANGE : BUDGET_MISSING);
        }
        if (minPriceIgnored) {
            warnings.add(BUDGET_MIN_IGNORED);
        }
        if (sizeKr == null) {
            warnings.add(SIZE_MISSING);
        }
        if (freeText != null && HARD_INTENT.matcher(freeText).find()) {
            warnings.add(FREE_TEXT_IS_SOFT.formatted(freeText));
        }
        return warnings;
    }

    static GoalDraft withWarning(GoalDraft draft, String warning, double confidenceCap) {
        List<String> warnings = new ArrayList<>(draft.warnings());
        warnings.add(0, warning);
        return new GoalDraft(
                draft.modelQuery(), draft.brand(), draft.modelKey(), draft.minCondition(),
                draft.hardMaxAmount(), draft.sizeKr(), draft.freeTextConditions(),
                Math.min(draft.confidence(), confidenceCap), warnings
        );
    }
}
