package com.vintic.backend.ai.purchase.harness;

import com.vintic.backend.ai.purchase.dto.GoalDraft;
import com.vintic.backend.ai.purchase.model.ModelAliases;
import com.vintic.backend.ai.purchase.parser.RuleBasedGoalParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// 규칙 기반 파서의 기준선. API 없이 매 빌드에서 돈다.
//
// 두 역할이다. (1) LLM 파서가 이겨야 할 숫자를 build/goal-parse-harness/rule.txt에 남긴다.
// (2) 규칙을 고치다 기준선이 무너지면 실패한다 - 아래 하한은 최초 측정값에서 여유를 뺀 값이고,
// 규칙이 좋아지면 올린다.
class RuleBasedGoalParserHarnessTest {

    private static final Path REPORT_DIRECTORY = Path.of("build", "goal-parse-harness");

    // 최초 측정(2026-09-10) 기준선에서 회귀를 잡기 위한 하한. 목표치(이슈 성공 기준 90%)가 아니다.
    private static final double MODEL_KEY_FLOOR = 0.85;
    private static final double AMOUNT_FLOOR = 0.85;
    private static final double ALL_FIELDS_FLOOR = 0.60;

    @Test
    void 픽스처_전체를_돌려_필드별_정확도를_측정하고_기준선을_지킨다() throws IOException {
        GoalParseHarnessFixtures.Document fixtures = GoalParseHarnessFixtures.load();
        RuleBasedGoalParser parser = new RuleBasedGoalParser(new ModelAliases());

        List<GoalParseHarnessScorer.CaseScore> scores = new ArrayList<>();
        for (GoalParseHarnessCase harnessCase : fixtures.cases()) {
            long startedAt = System.nanoTime();
            try {
                GoalDraft draft = parser.parse(harnessCase.text());
                scores.add(GoalParseHarnessScorer.score(harnessCase, draft, elapsedMs(startedAt)));
            } catch (RuntimeException e) {
                scores.add(GoalParseHarnessScorer.CaseScore.failed(harnessCase, elapsedMs(startedAt), e));
            }
        }

        GoalParseHarnessReport report = GoalParseHarnessReport.aggregate(
                "parser=rule, fixtures=" + fixtures.version(), scores, GoalParseHarnessReport.Usage.NONE);
        System.out.println(report.toText());
        Files.createDirectories(REPORT_DIRECTORY);
        Path reportPath = REPORT_DIRECTORY.resolve("rule.txt");
        Files.writeString(reportPath, report.toText(), StandardCharsets.UTF_8);
        System.out.println("리포트 저장: " + reportPath.toAbsolutePath());

        // 규칙 파서는 예외를 던지지 않는 것이 계약이다(파싱 실패가 등록을 막지 않는다).
        assertThat(report.failureCount()).isZero();
        assertThat(report.modelKeyAccuracy()).isGreaterThanOrEqualTo(MODEL_KEY_FLOOR);
        assertThat(report.amountAccuracy()).isGreaterThanOrEqualTo(AMOUNT_FLOOR);
        assertThat(report.allFieldsAccuracy()).isGreaterThanOrEqualTo(ALL_FIELDS_FLOOR);
    }

    private long elapsedMs(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }
}
