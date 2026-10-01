package com.vintic.backend.ai.purchase.match;

import com.vintic.backend.ai.purchase.model.ModelAliases;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// LLM 판정이 서버 검증 없이 나가지 않음을 고정한다. 검증은 항상 false 쪽으로만 뒤집는다.
class MatchResultValidatorTest {

    private final MatchResultValidator validator = new MatchResultValidator(new ModelAliases());
    private final MatchGoal nb990 = new MatchGoal("nb990", "New Balance 990", "New Balance", null);

    @Test
    void 규칙이_모르는_경우엔_LLM_판정을_그대로_쓴다() {
        LlmMatchResult llm = new LlmMatchResult(true, 0.8, "품번 M990GL6로 990 확인");
        AuctionListing listing = new AuctionListing(1L, "New Balance", null, null, "뉴발란스 M990GL6 그레이 270", "새상품");

        MatchResult result = validator.validate(llm, nb990, listing);

        assertThat(result.matched()).isTrue();
        assertThat(result.semanticScore()).isEqualTo(0.8);
        assertThat(result.reason()).isEqualTo("품번 M990GL6로 990 확인");
    }

    @Test
    void 제목이_다른_카탈로그_모델을_가리키면_LLM이_일치라_해도_제외한다() {
        LlmMatchResult llm = new LlmMatchResult(true, 0.9, "990 계열");
        AuctionListing listing = new AuctionListing(1L, "New Balance", "993", null, "뉴발란스 993 그레이 280", "");

        MatchResult result = validator.validate(llm, nb990, listing);

        assertThat(result.matched()).isFalse();
        assertThat(result.semanticScore()).isZero();
        assertThat(result.reason()).contains("993").contains("AI는 일치로 봤으나");
        assertThat(result.listingModelKey()).isEqualTo("nb993");
    }

    @Test
    void 박스만_판매와_브랜드_불일치는_LLM_판정과_무관하게_제외한다() {
        MatchGoal superstar = new MatchGoal("superstar", "Adidas Superstar", "Adidas", null);
        LlmMatchResult matched = new LlmMatchResult(true, 0.9, "슈퍼스타");

        MatchResult boxOnly = validator.validate(matched, nb990,
                new AuctionListing(1L, "New Balance", "990", null, "뉴발란스 990 박스", "신발박스 만 있습니다"));
        MatchResult goldenGoose = validator.validate(matched, superstar,
                new AuctionListing(2L, null, null, null, "골든구스 슈퍼스타 화이트 39", ""));

        assertThat(boxOnly.matched()).isFalse();
        assertThat(goldenGoose.matched()).isFalse();
        assertThat(goldenGoose.reason()).contains("Golden Goose");
    }

    @Test
    void 설명에서만_다른_모델이_보이는_건_규칙이_확신하지_못하므로_LLM을_따른다() {
        // "m990보다 작게 나옵니다" 같은 비교 언급. 제목·상품정보가 아니면 규칙은 개입하지 않는다.
        LlmMatchResult llm = new LlmMatchResult(false, 0.0, "매물은 991");
        AuctionListing listing = new AuctionListing(1L, "New Balance", null, null, "뉴발란스 991 그레이 270", "m990보다 작게 나옵니다");

        MatchResult result = validator.validate(llm, nb990, listing);

        assertThat(result.matched()).isFalse();
        assertThat(result.reason()).isEqualTo("매물은 991");
    }

    @Test
    void LLM이_불일치라_한_것을_일치로_바꾸지_않는다() {
        LlmMatchResult llm = new LlmMatchResult(false, 0.0, "슬리퍼");
        AuctionListing listing = new AuctionListing(1L, "New Balance", "990v6", null, "뉴발란스 990v6 280", "");

        assertThat(validator.validate(llm, nb990, listing).matched()).isFalse();
    }

    @Test
    void 점수는_0과_1_사이로_잘리고_불일치면_0이다() {
        AuctionListing listing = new AuctionListing(1L, "New Balance", "990v6", null, "뉴발란스 990v6 280", "");

        assertThat(validator.validate(new LlmMatchResult(true, 3.0, "x"), nb990, listing).semanticScore()).isEqualTo(1.0);
        assertThat(validator.validate(new LlmMatchResult(false, 0.9, "x"), nb990, listing).semanticScore()).isZero();
    }
}
