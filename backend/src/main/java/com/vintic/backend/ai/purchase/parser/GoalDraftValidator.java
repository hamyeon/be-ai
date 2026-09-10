package com.vintic.backend.ai.purchase.parser;

import com.vintic.backend.ai.purchase.dto.GoalCondition;
import com.vintic.backend.ai.purchase.dto.GoalDraft;
import com.vintic.backend.ai.purchase.model.BrandAliases;
import com.vintic.backend.ai.purchase.model.ModelAliases;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

// LLM 출력을 서버가 검증한다. Vision의 VisionEvidenceValidator와 같은 자리다.
//
// Structured Outputs가 형태는 보장하지만 값은 보장하지 않는다. 카탈로그에 없는 modelKey,
// 음수 예산, 사이즈 999 같은 값은 여기서 걷어내고 warnings로 알린다. 예외를 던지지 않는다 -
// 걷어낸 초안도 빈 폼보다는 낫다.
//
// 원칙: LLM이 낸 값 중 서버가 검증할 수 있는 건 전부 검증한다. modelKey가 카탈로그에 있으면
// brand/modelQuery는 LLM 값이 아니라 카탈로그 값을 쓴다.
@Component
@RequiredArgsConstructor
public class GoalDraftValidator {

    private static final long MIN_PLAUSIBLE_AMOUNT = 10_000L;
    private static final long MAX_PLAUSIBLE_AMOUNT = 100_000_000L;
    private static final int MIN_SIZE_KR = 200;
    private static final int MAX_SIZE_KR = 330;
    // 카탈로그 밖 모델은 Agent가 아무것도 못 하므로 확신도를 높게 두지 않는다.
    private static final double UNKNOWN_MODEL_CONFIDENCE_CAP = 0.6;

    private final ModelAliases modelAliases;

    GoalDraft validate(LlmGoalDraft llm) {
        Optional<ModelAliases.ModelInfo> catalogModel = modelAliases.byKey(llm.modelKey());
        boolean unknownKey = llm.modelKey() != null && catalogModel.isEmpty();

        String modelKey = catalogModel.map(ModelAliases.ModelInfo::modelKey).orElse(null);
        String modelQuery = catalogModel.map(ModelAliases.ModelInfo::fullName).orElse(blankToNull(llm.modelQuery()));
        String brand = catalogModel.map(ModelAliases.ModelInfo::brand)
                .orElse(BrandAliases.canonical(llm.brand()).orElse(null));

        GoalCondition condition = GoalCondition.fromLabel(llm.minCondition()).orElse(null);

        Long amount = llm.hardMaxAmount();
        boolean amountOutOfRange = amount != null && (amount < MIN_PLAUSIBLE_AMOUNT || amount > MAX_PLAUSIBLE_AMOUNT);
        if (amountOutOfRange) {
            amount = null;
        }

        Integer sizeKr = llm.sizeKr();
        if (sizeKr != null && (sizeKr < MIN_SIZE_KR || sizeKr > MAX_SIZE_KR)) {
            sizeKr = null;
        }

        String freeText = blankToNull(llm.freeTextConditions());

        List<String> warnings = GoalDraftWarnings.forDraft(modelKey, brand, amount, sizeKr, freeText, false, amountOutOfRange);
        if (modelKey == null && (unknownKey || modelQuery != null)) {
            // 모델 이름은 알아봤는데 시세 카탈로그에 없다 - "못 알아봄"과는 다른 안내가 필요하다.
            warnings.removeIf(w -> w.equals(GoalDraftWarnings.MODEL_UNKNOWN) || w.equals(GoalDraftWarnings.BRAND_ONLY));
            warnings.add(0, GoalDraftWarnings.MODEL_NOT_IN_CATALOG);
        }
        if (condition == null) {
            warnings.add(GoalDraftWarnings.CONDITION_MISSING);
        }

        double confidence = llm.confidence();
        if (modelKey == null) {
            confidence = Math.min(confidence, UNKNOWN_MODEL_CONFIDENCE_CAP);
        }
        return new GoalDraft(modelQuery, brand, modelKey, condition, amount, sizeKr, freeText, confidence, warnings);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
