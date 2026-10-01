package com.vintic.backend.ai.purchase.harness;

import java.util.List;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

// 케이스 점수를 모아 사람이 읽을 표로 만든다. 파서(규칙/LLM/모델)별로 한 장씩 남겨 비교한다.
final class GoalParseHarnessReport {

    record Usage(int apiCalls, int promptTokens, int completionTokens) {
        static final Usage NONE = new Usage(0, 0, 0);
    }

    private final String label;
    private final List<GoalParseHarnessScorer.CaseScore> scores;
    private final Usage usage;

    private GoalParseHarnessReport(String label, List<GoalParseHarnessScorer.CaseScore> scores, Usage usage) {
        this.label = label;
        this.scores = scores;
        this.usage = usage;
    }

    static GoalParseHarnessReport aggregate(String label, List<GoalParseHarnessScorer.CaseScore> scores, Usage usage) {
        return new GoalParseHarnessReport(label, List.copyOf(scores), usage);
    }

    double accuracy(Predicate<GoalParseHarnessScorer.CaseScore> field) {
        return ratio(field);
    }

    double modelKeyAccuracy() {
        return ratio(GoalParseHarnessScorer.CaseScore::modelKeyOk);
    }

    double amountAccuracy() {
        return ratio(GoalParseHarnessScorer.CaseScore::amountOk);
    }

    double allFieldsAccuracy() {
        return ratio(GoalParseHarnessScorer.CaseScore::allFieldsOk);
    }

    int failureCount() {
        return (int) scores.stream().filter(GoalParseHarnessScorer.CaseScore::failed).count();
    }

    String toText() {
        StringBuilder out = new StringBuilder();
        int total = scores.size();
        out.append("=== Goal 파싱 하네스: ").append(label).append(" ===\n");
        out.append("케이스 %d건, 실패(예외) %d건%n".formatted(total, failureCount()));
        out.append("brand         %s%n".formatted(pct(GoalParseHarnessScorer.CaseScore::brandOk)));
        out.append("modelKey      %s%n".formatted(pct(GoalParseHarnessScorer.CaseScore::modelKeyOk)));
        out.append("minCondition  %s%n".formatted(pct(GoalParseHarnessScorer.CaseScore::conditionOk)));
        out.append("hardMaxAmount %s%n".formatted(pct(GoalParseHarnessScorer.CaseScore::amountOk)));
        out.append("sizeKr        %s%n".formatted(pct(GoalParseHarnessScorer.CaseScore::sizeOk)));
        out.append("5필드 전부     %s%n".formatted(pct(GoalParseHarnessScorer.CaseScore::allFieldsOk)));
        out.append("자유조건 재현율 %.0f%% (키워드 있는 케이스 %d건)%n".formatted(
                100 * mean(s -> s.keywordRecall(), s -> !Double.isNaN(s.keywordRecall())),
                scores.stream().filter(s -> !Double.isNaN(s.keywordRecall())).count()));
        out.append("confidence 평균 - 5필드 정답 %.2f / 오답 %.2f%n".formatted(
                mean(GoalParseHarnessScorer.CaseScore::confidence, GoalParseHarnessScorer.CaseScore::allFieldsOk),
                mean(GoalParseHarnessScorer.CaseScore::confidence, s -> !s.allFieldsOk() && !s.failed())));
        out.append("지연 평균 %.0fms, 최대 %dms%n".formatted(
                mean(s -> s.latencyMs(), s -> true),
                scores.stream().mapToLong(GoalParseHarnessScorer.CaseScore::latencyMs).max().orElse(0)));
        if (usage.apiCalls() > 0) {
            out.append("API 호출 %d회, prompt %d / completion %d 토큰 (케이스당 %.0f)%n".formatted(
                    usage.apiCalls(), usage.promptTokens(), usage.completionTokens(),
                    (double) (usage.promptTokens() + usage.completionTokens()) / usage.apiCalls()));
        }
        out.append("\n--- 오답·실패 케이스 ---\n");
        for (GoalParseHarnessScorer.CaseScore score : scores) {
            if (score.allFieldsOk() && (Double.isNaN(score.keywordRecall()) || score.keywordRecall() >= 1.0)) {
                continue;
            }
            out.append("[").append(score.id()).append("] ").append(score.harnessCase().text()).append('\n');
            if (score.failed()) {
                out.append("    실패: ").append(score.failure()).append('\n');
                continue;
            }
            GoalParseHarnessCase.Expected expected = score.harnessCase().expected();
            if (!score.brandOk()) {
                out.append("    brand: 기대 %s / 실제 %s%n".formatted(expected.brand(), score.draft().brand()));
            }
            if (!score.modelKeyOk()) {
                out.append("    modelKey: 기대 %s / 실제 %s%n".formatted(expected.modelKey(), score.draft().modelKey()));
            }
            if (!score.conditionOk()) {
                out.append("    minCondition: 기대 %s / 실제 %s%n".formatted(expected.minCondition(), score.draft().minCondition()));
            }
            if (!score.amountOk()) {
                out.append("    hardMaxAmount: 기대 %s / 실제 %s%n".formatted(expected.hardMaxAmount(), score.draft().hardMaxAmount()));
            }
            if (!score.sizeOk()) {
                out.append("    sizeKr: 기대 %s / 실제 %s%n".formatted(expected.sizeKr(), score.draft().sizeKr()));
            }
            if (!Double.isNaN(score.keywordRecall()) && score.keywordRecall() < 1.0) {
                out.append("    freeText: 기대 키워드 %s / 실제 \"%s\"%n".formatted(
                        expected.freeTextKeywords(), score.draft().freeTextConditions()));
            }
        }
        return out.toString();
    }

    private String pct(Predicate<GoalParseHarnessScorer.CaseScore> field) {
        long ok = scores.stream().filter(field).count();
        return "%3d/%d (%.0f%%)".formatted(ok, scores.size(), 100.0 * ratio(field));
    }

    private double ratio(Predicate<GoalParseHarnessScorer.CaseScore> field) {
        if (scores.isEmpty()) {
            return 0.0;
        }
        return (double) scores.stream().filter(field).count() / scores.size();
    }

    private double mean(ToDoubleFunction<GoalParseHarnessScorer.CaseScore> value,
                        Predicate<GoalParseHarnessScorer.CaseScore> filter) {
        return scores.stream().filter(filter).mapToDouble(value).average().orElse(Double.NaN);
    }
}
