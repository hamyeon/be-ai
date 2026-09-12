package com.vintic.backend.ai.purchase.parser;

import com.vintic.backend.ai.purchase.dto.GoalDraft;
import com.vintic.backend.ai.purchase.model.ModelAliases;
import com.vintic.backend.common.exception.AiApiException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FallbackGoalParserTest {

    private final RuleBasedGoalParser rule = new RuleBasedGoalParser(new ModelAliases());

    @Test
    void 일차_파서가_성공하면_그_결과를_그대로_쓴다() {
        GoalDraft primaryResult = new GoalDraft("New Balance 990", "New Balance", "nb990", null, 150_000L, 270, null, 0.9, List.of());
        FallbackGoalParser parser = new FallbackGoalParser(text -> primaryResult, rule);

        assertThat(parser.parse("뉴발 990")).isSameAs(primaryResult);
    }

    @Test
    void 일차_파서가_던지면_규칙_기반_초안에_경고를_붙이고_확신도를_누른다() {
        FallbackGoalParser parser = new FallbackGoalParser(text -> {
            throw new AiApiException("OpenAI 오류 (status=503)");
        }, rule);

        GoalDraft draft = parser.parse("뉴발 990, A급 이상, 15만원 이하로 하나");

        assertThat(draft.modelKey()).isEqualTo("nb990");
        assertThat(draft.hardMaxAmount()).isEqualTo(150_000L);
        assertThat(draft.warnings().get(0)).isEqualTo(GoalDraftWarnings.AI_FALLBACK);
        assertThat(draft.confidence()).isLessThanOrEqualTo(0.5);
    }
}
