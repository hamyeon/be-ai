package com.vintic.backend.ai.purchase.match;

import com.vintic.backend.ai.purchase.model.ModelAliases;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

// LLM 판정을 서버가 검증한다. 파서의 GoalDraftValidator와 같은 자리다.
//
// 규칙이 확실하게 아는 것은 LLM이 뭐라 하든 규칙이 이긴다.
//   - 박스만 판매·아동용·의류·일괄 → matched=false
//   - 제목/상품정보가 다른 카탈로그 모델을 가리킴(990 요청에 993 매물) → matched=false
//   - 브랜드 불일치(골든구스 슈퍼스타) → matched=false
// 규칙이 모르는 것(별칭 없는 표기, "에어포스 슬리퍼"가 운동화가 아니라는 것)은 LLM 판단을 쓴다.
//
// 거짓 양성(사지 말아야 할 걸 산다)이 거짓 음성(살 만한 걸 놓친다)보다 비싸므로 검증은
// 항상 matched를 false 쪽으로만 뒤집는다. LLM이 false라고 한 것을 true로 바꾸지 않는다.
@Component
@RequiredArgsConstructor
public class MatchResultValidator {

    private final ModelAliases modelAliases;

    MatchResult validate(LlmMatchResult llm, MatchGoal goal, AuctionListing listing) {
        ListingSignals.Resolution resolution = ListingSignals.resolve(listing, modelAliases);

        Optional<String> negative = ListingSignals.hardNegative(listing, goal);
        if (negative.isPresent()) {
            return MatchResult.rejected(override(negative.get(), llm), resolution.modelKey());
        }

        Optional<String> listingBrand = ListingSignals.brandOf(listing);
        if (goal.brand() != null && listingBrand.isPresent() && !listingBrand.get().equals(goal.brand())) {
            return MatchResult.rejected(
                    override("브랜드 불일치 - 매물 %s, 요청 %s".formatted(listingBrand.get(), goal.brand()), llm),
                    resolution.modelKey());
        }

        if (goal.modelKey() != null && resolution.isConfident() && !goal.modelKey().equals(resolution.modelKey())) {
            String listingName = modelAliases.byKey(resolution.modelKey()).map(ModelAliases.ModelInfo::fullName)
                    .orElse(resolution.modelKey());
            return MatchResult.rejected(
                    override("매물은 %s로 판정 - 요청 모델(%s)과 다름".formatted(listingName, goal.modelQuery()), llm),
                    resolution.modelKey());
        }

        String reason = llm.reason() == null || llm.reason().isBlank() ? "AI 판정" : llm.reason().trim();
        return new MatchResult(llm.matched(), llm.matched() ? llm.semanticScore() : 0.0, reason, resolution.modelKey());
    }

    private String override(String ruleReason, LlmMatchResult llm) {
        if (llm.matched()) {
            return ruleReason + " (AI는 일치로 봤으나 규칙으로 제외)";
        }
        return ruleReason;
    }
}
