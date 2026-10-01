package com.vintic.backend.ai.purchase.parser;

import com.vintic.backend.ai.purchase.dto.GoalCondition;
import com.vintic.backend.ai.purchase.dto.GoalDraft;
import com.vintic.backend.ai.purchase.model.ModelAliases;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

// 규칙 하나하나의 성질을 고정한다. 전체 정확도는 RuleBasedGoalParserHarnessTest가 잰다.
class RuleBasedGoalParserTest {

    private final RuleBasedGoalParser parser = new RuleBasedGoalParser(new ModelAliases());

    @Test
    void 설계안_예시_문장을_구조화한다() {
        GoalDraft draft = parser.parse("뉴발 990, A급 이상, 15만원 이하로 하나");

        assertThat(draft.modelKey()).isEqualTo("nb990");
        assertThat(draft.modelQuery()).isEqualTo("New Balance 990");
        assertThat(draft.brand()).isEqualTo("New Balance");
        assertThat(draft.minCondition()).isEqualTo(GoalCondition.A);
        assertThat(draft.hardMaxAmount()).isEqualTo(150_000L);
        assertThat(draft.sizeKr()).isNull();
        assertThat(draft.freeTextConditions()).isNull();
        assertThat(draft.confidence()).isBetween(0.7, RuleBasedGoalParser.MAX_CONFIDENCE);
    }

    @ParameterizedTest
    @CsvSource({
            "15만원 이하, 150000",
            "15만, 150000",
            "15만5천원, 155000",
            "1.5만원, 15000",
            "5만원대, 60000",
            "10만원대, 200000",
            "15만원대, 250000",
            "'200,000원 이하', 200000",
            "130000원까지, 130000",
            "20만~25만 사이, 250000",
            "최소 30만 최대 50만, 500000"
    })
    void 금액_표기를_원_단위_상한으로_읽는다(String text, long expected) {
        assertThat(parser.parse("삼바 " + text).hardMaxAmount()).isEqualTo(expected);
    }

    @Test
    void 최소_금액만_있으면_상한은_비우고_경고한다() {
        GoalDraft draft = parser.parse("삼바 270 10만원 이상으로");

        assertThat(draft.hardMaxAmount()).isNull();
        assertThat(draft.warnings()).contains(GoalDraftWarnings.BUDGET_MIN_IGNORED);
    }

    @Test
    void 품번과_사이즈_숫자를_금액으로_읽지_않는다() {
        assertThat(parser.parse("닥터마틴 1461 3홀 260 15만").hardMaxAmount()).isEqualTo(150_000L);
        assertThat(parser.parse("뉴발란스 2002R 프로틴 255 미착용만").hardMaxAmount()).isNull();
        assertThat(parser.parse("사이즈 270만 찾아요").hardMaxAmount()).isNull();
    }

    @ParameterizedTest
    @CsvSource({
            "조던1 270 새거급, 270",
            "척70 270mm, 270",
            "가젤 240 사이즈, 240",
            "삼바 255 130000원, 255",
            "코르테즈 275, 275"
    })
    void 사이즈를_읽는다(String text, int expected) {
        assertThat(parser.parse(text).sizeKr()).isEqualTo(expected);
    }

    @Test
    void 금액_안의_숫자나_품번을_사이즈로_읽지_않는다() {
        assertThat(parser.parse("삼바 250000원").sizeKr()).isNull();
        assertThat(parser.parse("뉴발란스 2002R").sizeKr()).isNull();
        assertThat(parser.parse("뉴발 990").sizeKr()).isNull();
    }

    @ParameterizedTest
    @CsvSource({
            "A급 이상, A",
            "b급도 괜찮음, B",
            "S급, S",
            "새거급으로, DS",
            "미착용만, DS",
            "거의 새거, S",
            "극상, S",
            "상태 좋은 걸로, A",
            "깨끗한 거, A",
            "사용감 적은거, A",
            "사용감 있어도 됨, B",
            "막신을 거, C"
    })
    void 상태_표현을_등급으로_읽는다(String text, GoalCondition expected) {
        assertThat(parser.parse("삼바 " + text).minCondition()).isEqualTo(expected);
    }

    @Test
    void 상태_상관없음은_등급을_비운다() {
        assertThat(parser.parse("뉴발 993 상태 상관없음").minCondition()).isNull();
    }

    @Test
    void 구조화_필드로_쓴_구간과_군더더기를_뺀_나머지가_자유_조건이_된다() {
        GoalDraft draft = parser.parse("조던1 시카고 270 새거급으로 40만원까지 하나 사고 싶어요");

        assertThat(draft.freeTextConditions()).isEqualTo("시카고");
    }

    @Test
    void 필수_의도가_담긴_자유_조건은_v1에서_참고_사항이라고_경고한다() {
        GoalDraft draft = parser.parse("삼바 흰색 10만원 안쪽. 박스는 꼭 있어야 됨");

        assertThat(draft.freeTextConditions()).contains("박스").contains("흰색");
        assertThat(draft.warnings()).anyMatch(w -> w.contains("참고 사항"));
    }

    @Test
    void 브랜드만_알아보면_모델은_비우고_브랜드만_채운다() {
        GoalDraft draft = parser.parse("아식스 젤 1130 260 사이즈 8만원 이하");

        assertThat(draft.modelKey()).isNull();
        assertThat(draft.brand()).isEqualTo("Asics");
        assertThat(draft.hardMaxAmount()).isEqualTo(80_000L);
        assertThat(draft.sizeKr()).isEqualTo(260);
        assertThat(draft.warnings()).contains(GoalDraftWarnings.BRAND_ONLY);
    }

    @Test
    void 아무것도_못_알아본_문장도_예외_없이_초안을_돌려준다() {
        GoalDraft draft = parser.parse("좋은 신발 하나 부탁해요");

        assertThat(draft.modelKey()).isNull();
        assertThat(draft.hardMaxAmount()).isNull();
        assertThat(draft.confidence()).isLessThan(0.5);
        assertThat(draft.warnings()).contains(GoalDraftWarnings.MODEL_UNKNOWN, GoalDraftWarnings.BUDGET_MISSING);
    }

    @Test
    void 빈_입력은_빈_초안이다() {
        GoalDraft draft = parser.parse("   ");

        assertThat(draft.confidence()).isZero();
        assertThat(draft.warnings()).isNotEmpty();
    }

    @Test
    void 확신도는_상한을_넘지_않는다() {
        GoalDraft draft = parser.parse("뉴발 990 A급 270 15만원 이하");

        assertThat(draft.confidence()).isLessThanOrEqualTo(RuleBasedGoalParser.MAX_CONFIDENCE);
    }
}
