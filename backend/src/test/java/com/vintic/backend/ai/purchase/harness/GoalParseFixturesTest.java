package com.vintic.backend.ai.purchase.harness;

import com.vintic.backend.ai.purchase.dto.GoalCondition;
import com.vintic.backend.ai.purchase.model.ModelAliases;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// 픽스처 자체의 정합성. 정답이 카탈로그 밖 키를 가리키거나 등급 표기가 틀리면 채점이 무의미하다.
class GoalParseFixturesTest {

    private final ModelAliases modelAliases = new ModelAliases();

    @Test
    void 픽스처가_로딩되고_케이스마다_문장과_기대값이_있다() {
        GoalParseHarnessFixtures.Document document = GoalParseHarnessFixtures.load();

        assertThat(document.cases()).hasSizeGreaterThanOrEqualTo(40);
        assertThat(document.cases()).allSatisfy(harnessCase -> {
            assertThat(harnessCase.id()).isNotBlank();
            assertThat(harnessCase.text()).isNotBlank();
            assertThat(harnessCase.expected()).isNotNull();
            assertThat(harnessCase.expected().freeTextKeywords()).isNotNull();
        });
    }

    @Test
    void 케이스_id는_중복되지_않는다() {
        List<String> ids = GoalParseHarnessFixtures.load().cases().stream().map(GoalParseHarnessCase::id).toList();

        assertThat(ids).doesNotHaveDuplicates();
    }

    @Test
    void 기대_modelKey는_전부_카탈로그에_있고_기대_등급은_유효하다() {
        GoalParseHarnessFixtures.Document document = GoalParseHarnessFixtures.load();

        assertThat(document.cases()).allSatisfy(harnessCase -> {
            String modelKey = harnessCase.expected().modelKey();
            if (modelKey != null) {
                assertThat(modelAliases.isKnownKey(modelKey))
                        .as("%s: 카탈로그에 없는 modelKey %s", harnessCase.id(), modelKey).isTrue();
            }
            String condition = harnessCase.expected().minCondition();
            if (condition != null) {
                assertThat(GoalCondition.fromLabel(condition))
                        .as("%s: 잘못된 등급 %s", harnessCase.id(), condition).isPresent();
            }
        });
    }

    @Test
    void 카탈로그_밖_모델과_브랜드만_있는_케이스가_포함돼_있다() {
        // 파서가 "모른다"고 말하는지도 재야 한다. 전부 정답이 있는 셋이면 과잉 확신을 못 잡는다.
        List<GoalParseHarnessCase> cases = GoalParseHarnessFixtures.load().cases();

        assertThat(cases).anySatisfy(c -> assertThat(c.expected().modelKey()).isNull());
        assertThat(cases).anySatisfy(c -> {
            assertThat(c.expected().modelKey()).isNull();
            assertThat(c.expected().brand()).isNotNull();
        });
        assertThat(cases).anySatisfy(c -> assertThat(c.expected().hardMaxAmount()).isNull());
    }
}
