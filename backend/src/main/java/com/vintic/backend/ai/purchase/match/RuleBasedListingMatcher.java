package com.vintic.backend.ai.purchase.match;

import com.vintic.backend.ai.purchase.model.ModelAliases;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

// 규칙 기반 Matcher. 별칭 표와 정규식만으로 판정한다. API 없음, 결정적.
//
// 역할: 백엔드 루프의 Fake 구현(설계안 6-4)이자 LLM Matcher가 이겨야 할 기준선.
// 못 하는 것: "에어포스 슬리퍼"가 에어포스1 운동화가 아니라는 것, 별칭 표에 없는 오타,
// "흰색"과 "화이트"가 같다는 것. 그런 케이스가 하네스에서 LLM의 가치를 잰다.
@Component
@RequiredArgsConstructor
public class RuleBasedListingMatcher implements ListingMatcher {

    private static final double SCORE_STRUCTURED = 0.7;
    private static final double SCORE_TITLE = 0.6;
    private static final double SCORE_BRAND_ONLY = 0.3;
    private static final double FREE_TEXT_WEIGHT = 0.3;

    private final ModelAliases modelAliases;

    @Override
    public MatchResult evaluate(MatchGoal goal, AuctionListing listing) {
        ListingSignals.Resolution resolution = ListingSignals.resolve(listing, modelAliases);

        Optional<String> negative = ListingSignals.hardNegative(listing, goal);
        if (negative.isPresent()) {
            return MatchResult.rejected(negative.get(), resolution.modelKey());
        }

        Optional<String> listingBrand = ListingSignals.brandOf(listing);
        if (goal.brand() != null && listingBrand.isPresent() && !listingBrand.get().equals(goal.brand())) {
            return MatchResult.rejected(
                    "브랜드 불일치 - 매물 %s, 요청 %s".formatted(listingBrand.get(), goal.brand()), resolution.modelKey());
        }

        if (goal.modelKey() == null) {
            // 모델 미지정 Goal: 브랜드가 맞으면 후보. 모델 판정이 없으니 점수는 낮다.
            if (goal.brand() == null || listingBrand.map(goal.brand()::equals).orElse(false)) {
                return new MatchResult(true, SCORE_BRAND_ONLY + freeTextBonus(goal, listing),
                        "모델 미지정 Goal - 브랜드 일치로 후보 유지", resolution.modelKey());
            }
            return MatchResult.rejected("모델 미지정 Goal - 매물에서 브랜드를 확인할 수 없음", resolution.modelKey());
        }

        if (resolution.modelKey() == null) {
            return MatchResult.rejected("매물에서 카탈로그 모델을 확인할 수 없음", null);
        }
        if (!resolution.modelKey().equals(goal.modelKey())) {
            String listingName = modelAliases.byKey(resolution.modelKey()).map(ModelAliases.ModelInfo::fullName)
                    .orElse(resolution.modelKey());
            return MatchResult.rejected(
                    "매물은 %s로 판정 - 요청 모델(%s)과 다름".formatted(listingName, goal.modelQuery()), resolution.modelKey());
        }
        // 설명에서만 모델이 잡힌 경우는 믿지 않는다. 검색용 브랜드 나열, 사이즈 비교("평소 가젤 255
        // 신는데"), 다른 모델과의 비교가 전부 여기서 나온다. 최초 하네스에서 거짓 양성 4건 중 3건이
        // 이 경로였다. 제목에 없는 모델을 설명으로 알아보는 건 LLM Matcher의 몫이다.
        if (resolution.source() == ListingSignals.Source.DESCRIPTION) {
            return MatchResult.rejected("설명에서만 모델이 언급됨 - 제목·상품 정보에 근거 없음", resolution.modelKey());
        }

        double base = switch (resolution.source()) {
            case STRUCTURED -> SCORE_STRUCTURED;
            case TITLE -> SCORE_TITLE;
            case DESCRIPTION, NONE -> 0.0;
        };
        String where = resolution.source() == ListingSignals.Source.STRUCTURED ? "상품 정보" : "제목";
        return new MatchResult(true, base + freeTextBonus(goal, listing),
                "%s에서 %s 확인 (표기 '%s')".formatted(where, goal.modelQuery(), resolution.matchedAlias()),
                resolution.modelKey());
    }

    // 자유 조건 토큰이 매물 텍스트에 얼마나 나타나는가. 동의어(흰색=화이트)는 모른다.
    private double freeTextBonus(MatchGoal goal, AuctionListing listing) {
        List<String> tokens = tokens(goal.freeTextConditions());
        if (tokens.isEmpty()) {
            return 0.0;
        }
        String haystack = ModelAliases.normalize(listing.allText());
        long hits = tokens.stream().filter(haystack::contains).count();
        return FREE_TEXT_WEIGHT * hits / tokens.size();
    }

    private List<String> tokens(String freeText) {
        if (freeText == null || freeText.isBlank()) {
            return List.of();
        }
        return Arrays.stream(freeText.split("[\\s,./]+"))
                .map(ModelAliases::normalize)
                .filter(token -> token.length() >= 2)
                .toList();
    }
}
