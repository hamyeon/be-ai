package com.vintic.backend.ai.purchase.parser;

import com.vintic.backend.ai.purchase.dto.GoalCondition;
import com.vintic.backend.ai.purchase.dto.GoalDraft;
import com.vintic.backend.ai.purchase.model.ModelAliases;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// LLM 출력이 서버 검증을 거치지 않고 나가는 길이 없음을 고정한다.
class GoalDraftValidatorTest {

    private final GoalDraftValidator validator = new GoalDraftValidator(new ModelAliases());

    @Test
    void 카탈로그_키면_brand와_modelQuery는_LLM_값이_아니라_카탈로그_값을_쓴다() {
        GoalDraft draft = validator.validate(new LlmGoalDraft(
                "nb990", "NB 990v6 (LLM이 쓴 이름)", "뉴발란스", "A", 150_000L, 270, null, 0.9));

        assertThat(draft.modelKey()).isEqualTo("nb990");
        assertThat(draft.modelQuery()).isEqualTo("New Balance 990");
        assertThat(draft.brand()).isEqualTo("New Balance");
        assertThat(draft.minCondition()).isEqualTo(GoalCondition.A);
        assertThat(draft.confidence()).isEqualTo(0.9);
    }

    @Test
    void 카탈로그_밖_키는_비우고_시세_없음_경고를_붙이며_확신도를_누른다() {
        GoalDraft draft = validator.validate(new LlmGoalDraft(
                "yeezy350", "Adidas Yeezy 350", "Adidas", "DS", 300_000L, 270, "제브라", 0.95));

        assertThat(draft.modelKey()).isNull();
        assertThat(draft.modelQuery()).isEqualTo("Adidas Yeezy 350");
        assertThat(draft.brand()).isEqualTo("Adidas");
        assertThat(draft.warnings()).contains(GoalDraftWarnings.MODEL_NOT_IN_CATALOG);
        assertThat(draft.confidence()).isLessThanOrEqualTo(0.6);
    }

    @Test
    void 비정상_금액과_사이즈는_비운다() {
        GoalDraft draft = validator.validate(new LlmGoalDraft(
                "sambaog", null, null, null, -5L, 999, null, 0.8));

        assertThat(draft.hardMaxAmount()).isNull();
        assertThat(draft.sizeKr()).isNull();
        assertThat(draft.warnings()).contains(GoalDraftWarnings.BUDGET_OUT_OF_RANGE, GoalDraftWarnings.SIZE_MISSING);
    }

    @Test
    void 모르는_등급_표기와_모르는_브랜드는_비운다() {
        GoalDraft draft = validator.validate(new LlmGoalDraft(
                null, null, "Zara", "MINT", 500_000L, null, null, 0.7));

        assertThat(draft.minCondition()).isNull();
        assertThat(draft.brand()).isNull();
        assertThat(draft.warnings()).contains(GoalDraftWarnings.CONDITION_MISSING);
    }

    @Test
    void 확신도는_0과_1_사이로_잘린다() {
        assertThat(validator.validate(new LlmGoalDraft("nb990", null, null, null, 100_000L, 270, null, 7.0)).confidence())
                .isEqualTo(1.0);
        assertThat(validator.validate(new LlmGoalDraft("nb990", null, null, null, 100_000L, 270, null, -1.0)).confidence())
                .isEqualTo(0.0);
    }
}
