package com.vintic.backend.ai.purchase.match;

import com.vintic.backend.ai.purchase.model.ModelAliases;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// 규칙 하나하나의 성질. 전체 정확도는 RuleBasedListingMatcherHarnessTest가 잰다.
class RuleBasedListingMatcherTest {

    private final RuleBasedListingMatcher matcher = new RuleBasedListingMatcher(new ModelAliases());
    private final MatchGoal nb990 = new MatchGoal("nb990", "New Balance 990", "New Balance", null);

    @Test
    void 상품_정보의_모델이_Goal과_같으면_일치한다() {
        MatchResult result = matcher.evaluate(nb990,
                listing("New Balance", "990v6", "뉴발란스 990v6 트리플블랙 280", "새상품급"));

        assertThat(result.matched()).isTrue();
        assertThat(result.listingModelKey()).isEqualTo("nb990");
        assertThat(result.semanticScore()).isEqualTo(0.7);
        assertThat(result.reason()).contains("상품 정보");
    }

    @Test
    void 다른_카탈로그_모델이면_불일치하고_이유에_그_모델을_적는다() {
        MatchResult result = matcher.evaluate(nb990,
                listing("New Balance", "993", "뉴발란스 993 MIU 그레이 280", "그레이"));

        assertThat(result.matched()).isFalse();
        assertThat(result.listingModelKey()).isEqualTo("nb993");
        assertThat(result.reason()).contains("993").contains("990");
    }

    @Test
    void 설명에서만_모델이_잡히면_규칙은_믿지_않는다() {
        // 거짓 양성이 거짓 음성보다 비싸다. 설명만으로 알아보는 건 LLM의 몫이고, 규칙은 놓치는 쪽을 택한다.
        AuctionListing descriptionOnly = listing("New Balance", null, "뉴발란스 신발 판매합니다", "뉴발란스 990v5 트리플 블랙 230");
        AuctionListing spam = listing("Asics", null, "아식스 젤 큐물러스 18", "런닝화입니다. 나이키 아디다스 뉴발란스 990 컨버스");

        assertThat(matcher.evaluate(nb990, descriptionOnly).matched()).isFalse();
        assertThat(matcher.evaluate(nb990, descriptionOnly).listingModelKey()).isEqualTo("nb990");
        assertThat(matcher.evaluate(nb990, spam).matched()).isFalse();
    }

    @Test
    void 브랜드가_다르면_모델_이름이_같아도_불일치한다() {
        MatchGoal superstar = new MatchGoal("superstar", "Adidas Superstar", "Adidas", null);

        MatchResult result = matcher.evaluate(superstar,
                listing(null, null, "골든구스 슈퍼스타 화이트 실버탭 39사이즈", "백화점 정품"));

        assertThat(result.matched()).isFalse();
        assertThat(result.reason()).contains("브랜드 불일치");
    }

    @Test
    void 박스만_판매_아동_의류_일괄은_모델이_맞아도_불일치한다() {
        assertThat(matcher.evaluate(nb990, listing("New Balance", "990", "뉴발란스 990 박스", "신발박스 만 있으니 참고")).reason())
                .contains("박스만");
        assertThat(matcher.evaluate(nb990, listing("New Balance", "990", "아동 뉴발란스 990 160", "아이가 신던")).reason())
                .contains("아동");
        assertThat(matcher.evaluate(nb990, listing("New Balance", null, "뉴발란스 990 후드집업 XL", "옷")).reason())
                .contains("의류");
        assertThat(matcher.evaluate(nb990, listing(null, null, "신발정리 일괄", "1. 뉴발 990 270 2. 삼바 280")).reason())
                .contains("일괄");
    }

    @Test
    void 박스만_개봉이나_훼손은_박스만_판매가_아니다() {
        MatchResult result = matcher.evaluate(nb990,
                listing("New Balance", "990v6", "뉴발란스 990v6 새상품", "박스만 개봉했고 박스만 조금 찌그러짐"));

        assertThat(result.matched()).isTrue();
    }

    @Test
    void 슬리퍼_표식은_운동화_Goal에서만_부정_신호다() {
        MatchGoal tasman = new MatchGoal("tasman", "UGG Tasman", "UGG", null);
        MatchGoal af1 = new MatchGoal("airforce1", "Nike Air Force 1", "Nike", null);

        assertThat(matcher.evaluate(tasman, listing("UGG", "타스만", "UGG 어그 타스만 슬리퍼 280", "인기 슬리퍼")).matched()).isTrue();
        assertThat(matcher.evaluate(af1, listing("Nike", null, "나이키 에어포스 슬리퍼 새상품", "에어포스 1 러버 슬리퍼")).matched()).isFalse();
    }

    @Test
    void 자유_조건_토큰이_매물에_있으면_점수가_오른다() {
        MatchGoal withFreeText = new MatchGoal("nb990", "New Balance 990", "New Balance", "블랙, 박스");
        AuctionListing both = listing("New Balance", "990v6", "뉴발란스 990v6 트리플블랙 280", "택이랑 박스 같이 보내드려요");
        AuctionListing none = listing("New Balance", "990v6", "뉴발란스 990v6 그레이 280", "신발 단품");

        assertThat(matcher.evaluate(withFreeText, both).semanticScore()).isEqualTo(1.0);
        assertThat(matcher.evaluate(withFreeText, none).semanticScore()).isEqualTo(0.7);
    }

    @Test
    void 모델_미지정_Goal은_브랜드만_맞으면_낮은_점수로_일치한다() {
        MatchGoal nikeOnly = new MatchGoal(null, null, "Nike", null);

        MatchResult nike = matcher.evaluate(nikeOnly, listing("Nike", "덩크 로우", "나이키 덩크 로우 260", "새상품"));
        MatchResult adidas = matcher.evaluate(nikeOnly, listing("Adidas", "삼바", "아디다스 삼바 240", "새상품"));

        assertThat(nike.matched()).isTrue();
        assertThat(nike.semanticScore()).isEqualTo(0.3);
        assertThat(adidas.matched()).isFalse();
    }

    @Test
    void 카탈로그_모델을_전혀_못_찾으면_불일치한다() {
        MatchResult result = matcher.evaluate(nb990, listing("New Balance", "퓨어셀", "뉴발란스 퓨어셀 러닝화 240", "주황색"));

        assertThat(result.matched()).isFalse();
        assertThat(result.listingModelKey()).isNull();
    }

    // Day 5 확인: PurchaseGoalCandidateRanker.toListing()은 Product에 title 필드가 없어 항상
    // null을 넘긴다 - structured(brand+model) 해석이 title 유무와 무관하게 동작하는지 확인한다.
    @Test
    void title가_null이어도_브랜드와_모델만으로_판정한다() {
        AuctionListing listing = new AuctionListing(1L, "New Balance", "990v6", "트리플블랙", null, "설명 없음");

        MatchResult result = matcher.evaluate(nb990, listing);

        assertThat(result.matched()).isTrue();
        assertThat(result.listingModelKey()).isEqualTo("nb990");
        assertThat(result.reason()).contains("상품 정보");
    }

    private AuctionListing listing(String brand, String model, String title, String description) {
        return new AuctionListing(1L, brand, model, null, title, description);
    }
}
