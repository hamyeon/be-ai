package com.vintic.backend.ai.vision.harness;

import com.vintic.backend.ai.vision.dto.ConditionGrade;
import com.vintic.backend.ai.vision.dto.VisionAnalysisResult;
import com.vintic.backend.ai.vision.harness.VisionHarnessScorer.Field;
import com.vintic.backend.ai.vision.harness.VisionHarnessScorer.Outcome;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VisionHarnessScorerTest {

    private VisionHarnessCase caseWith(VisionHarnessCase.Expected expected) {
        return new VisionHarnessCase("test-case", List.of("https://example.com/a.webp"), null, expected, "테스트");
    }

    private VisionAnalysisResult resultWith(String brand, String modelName, Integer size,
                                            Boolean boxIncluded, ConditionGrade grade) {
        return new VisionAnalysisResult(brand, modelName, null, size, null, grade, boxIncluded,
                null, null, List.of(), List.of());
    }

    @Test
    void 브랜드는_표기가_달라도_포함_관계면_정답으로_본다() {
        VisionHarnessCase harnessCase = caseWith(new VisionHarnessCase.Expected(
                List.of("Nike", "Jordan"), null, null, null, null));

        VisionHarnessScorer.CaseScore score = VisionHarnessScorer.score(
                harnessCase, resultWith("NIKE Air Jordan", null, null, null, null), 100L);

        assertThat(score.outcomes().get(Field.BRAND)).isEqualTo(Outcome.CORRECT);
    }

    @Test
    void 다른_브랜드로_답하면_오답이다() {
        VisionHarnessCase harnessCase = caseWith(new VisionHarnessCase.Expected(
                List.of("New Balance"), null, null, null, null));

        VisionHarnessScorer.CaseScore score = VisionHarnessScorer.score(
                harnessCase, resultWith("Asics", null, null, null, null), 100L);

        assertThat(score.outcomes().get(Field.BRAND)).isEqualTo(Outcome.WRONG);
    }

    @Test
    void 모델명은_키워드가_전부_들어있어야_정답이다() {
        VisionHarnessCase harnessCase = caseWith(new VisionHarnessCase.Expected(
                null, List.of("chuck", "70"), null, null, null));

        VisionHarnessScorer.CaseScore matched = VisionHarnessScorer.score(
                harnessCase, resultWith(null, "Chuck Taylor All Star 70 Hi", null, null, null), 100L);
        VisionHarnessScorer.CaseScore partial = VisionHarnessScorer.score(
                harnessCase, resultWith(null, "Chuck Taylor All Star", null, null, null), 100L);

        assertThat(matched.outcomes().get(Field.MODEL_NAME)).isEqualTo(Outcome.CORRECT);
        assertThat(partial.outcomes().get(Field.MODEL_NAME)).isEqualTo(Outcome.WRONG);
    }

    @Test
    void 값을_채우지_않은_것과_틀리게_채운_것을_구분한다() {
        VisionHarnessCase harnessCase = caseWith(new VisionHarnessCase.Expected(
                null, null, 270, null, null));

        VisionHarnessScorer.CaseScore abstained = VisionHarnessScorer.score(
                harnessCase, resultWith(null, null, null, null, null), 100L);
        VisionHarnessScorer.CaseScore wrong = VisionHarnessScorer.score(
                harnessCase, resultWith(null, null, 250, null, null), 100L);

        assertThat(abstained.outcomes().get(Field.SIZE)).isEqualTo(Outcome.ABSTAINED);
        assertThat(wrong.outcomes().get(Field.SIZE)).isEqualTo(Outcome.WRONG);
    }

    @Test
    void 정답_라벨이_없는_필드는_채점에서_제외한다() {
        VisionHarnessCase harnessCase = caseWith(new VisionHarnessCase.Expected(null, null, null, null, null));

        VisionHarnessScorer.CaseScore score = VisionHarnessScorer.score(
                harnessCase, resultWith("Nike", "Air Force 1", 270, true, ConditionGrade.A), 100L);

        assertThat(score.outcomes().values()).containsOnly(Outcome.NOT_LABELED);
    }

    @Test
    void 컨디션_등급은_한_단계_차이면_근사로_센다() {
        VisionHarnessCase harnessCase = caseWith(new VisionHarnessCase.Expected(null, null, null, null, "A"));

        VisionHarnessScorer.CaseScore near = VisionHarnessScorer.score(
                harnessCase, resultWith(null, null, null, null, ConditionGrade.B), 100L);
        VisionHarnessScorer.CaseScore wrong = VisionHarnessScorer.score(
                harnessCase, resultWith(null, null, null, null, ConditionGrade.C), 100L);

        assertThat(near.outcomes().get(Field.CONDITION_GRADE)).isEqualTo(Outcome.NEAR);
        assertThat(wrong.outcomes().get(Field.CONDITION_GRADE)).isEqualTo(Outcome.WRONG);
    }

    @Test
    void 컨디션_등급_UNKNOWN은_기권으로_본다() {
        VisionHarnessCase harnessCase = caseWith(new VisionHarnessCase.Expected(null, null, null, null, "B"));

        VisionHarnessScorer.CaseScore score = VisionHarnessScorer.score(
                harnessCase, resultWith(null, null, null, null, ConditionGrade.UNKNOWN), 100L);

        assertThat(score.outcomes().get(Field.CONDITION_GRADE)).isEqualTo(Outcome.ABSTAINED);
    }

    @Test
    void 리포트는_필드별_응답률과_정확도를_집계한다() {
        VisionHarnessCase harnessCase = caseWith(new VisionHarnessCase.Expected(null, null, 270, null, null));

        VisionHarnessReport report = VisionHarnessReport.aggregate("test", List.of(
                VisionHarnessScorer.score(harnessCase, resultWith(null, null, 270, null, null), 100L),
                VisionHarnessScorer.score(harnessCase, resultWith(null, null, 250, null, null), 200L),
                VisionHarnessScorer.score(harnessCase, resultWith(null, null, null, null, null), 300L)
        ));

        VisionHarnessReport.FieldStat sizeStat = report.fieldStats().get(Field.SIZE);
        assertThat(sizeStat.scored()).isEqualTo(3);
        assertThat(sizeStat.fillRate()).isEqualTo(2.0 / 3);   // 3건 중 2건만 값을 채움
        assertThat(sizeStat.precision()).isEqualTo(0.5);      // 채운 2건 중 1건만 정답
        assertThat(sizeStat.accuracy()).isEqualTo(1.0 / 3);
        assertThat(report.averageLatencyMs()).isEqualTo(200L);
    }
}
