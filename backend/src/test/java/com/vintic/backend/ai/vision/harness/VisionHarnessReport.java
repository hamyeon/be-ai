package com.vintic.backend.ai.vision.harness;

import com.vintic.backend.ai.vision.harness.VisionHarnessScorer.CaseScore;
import com.vintic.backend.ai.vision.harness.VisionHarnessScorer.Field;
import com.vintic.backend.ai.vision.harness.VisionHarnessScorer.Outcome;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

// 케이스별 채점 결과를 필드 단위로 모아 표로 출력한다.
//
// 프롬프트를 바꿀 때마다 이 표를 남겨두고 비교하는 게 이 하네스의 목적이다.
// 특히 "오답(환각)" 열은 값이 늘어나면 무조건 나쁜 신호로 본다 - 응답률만 올리는 변경을 걸러내기 위한 것.
public record VisionHarnessReport(
        String label,
        int caseCount,
        int failureCount,
        long averageLatencyMs,
        Map<Field, FieldStat> fieldStats,
        List<CaseScore> caseScores,
        Usage usage,
        JsonCompliance jsonCompliance
) {

    // 호출 비용을 재기 위한 집계. 정확도가 올라도 비용이 몇 배로 뛰면 채택할 수 없으므로 같이 본다.
    public record Usage(int apiCalls, int promptTokens, int completionTokens) {

        public static final Usage EMPTY = new Usage(0, 0, 0);

        public int totalTokens() {
            return promptTokens + completionTokens;
        }
    }

    public record FieldStat(int correct, int near, int wrong, int abstained, int notLabeled) {

        public int scored() {
            return correct + near + wrong + abstained;
        }

        public int answered() {
            return correct + near + wrong;
        }

        // 채점 대상 중 값을 채운 비율
        public double fillRate() {
            return scored() == 0 ? 0.0 : (double) answered() / scored();
        }

        // 값을 채운 것 중 정확히 맞은 비율. 낮으면 곧 환각률이 높다는 뜻.
        public double precision() {
            return answered() == 0 ? 0.0 : (double) correct / answered();
        }

        // 채점 대상 전체 중 맞은 비율
        public double accuracy() {
            return scored() == 0 ? 0.0 : (double) correct / scored();
        }
    }

    public static VisionHarnessReport aggregate(String label, List<CaseScore> caseScores) {
        return aggregate(label, caseScores, Usage.EMPTY);
    }

    public static VisionHarnessReport aggregate(String label, List<CaseScore> caseScores, Usage usage) {
        Map<Field, int[]> counters = new EnumMap<>(Field.class);
        for (Field field : Field.values()) {
            counters.put(field, new int[Outcome.values().length]);
        }
        for (CaseScore caseScore : caseScores) {
            caseScore.outcomes().forEach((field, outcome) -> counters.get(field)[outcome.ordinal()]++);
        }

        Map<Field, FieldStat> fieldStats = new EnumMap<>(Field.class);
        counters.forEach((field, counts) -> fieldStats.put(field, new FieldStat(
                counts[Outcome.CORRECT.ordinal()],
                counts[Outcome.NEAR.ordinal()],
                counts[Outcome.WRONG.ordinal()],
                counts[Outcome.ABSTAINED.ordinal()],
                counts[Outcome.NOT_LABELED.ordinal()]
        )));

        int failureCount = (int) caseScores.stream().filter(CaseScore::isFailure).count();
        long averageLatencyMs = caseScores.isEmpty() ? 0L
                : Math.round(caseScores.stream().mapToLong(CaseScore::latencyMs).average().orElse(0.0));

        return new VisionHarnessReport(label, caseScores.size(), failureCount, averageLatencyMs,
                fieldStats, caseScores, usage, JsonCompliance.from(caseScores));
    }

    // Structured Outputs를 쓰면 형식은 보장된다는 게 전제지만, 응답이 잘리거나 스키마를 잘못
    // 만들면 깨진다. 프롬프트를 바꿀 때 이 값이 떨어지면 형식을 건드린 것이므로 바로 알아야 한다.
    public record JsonCompliance(int responded, int malformed) {

        public static JsonCompliance from(List<CaseScore> caseScores) {
            // API가 거절해 응답 자체를 못 받은 케이스는 분모에서 뺀다. 429를 맞은 걸
            // "형식을 어겼다"고 세면 프롬프트 품질과 무관한 값이 섞인다.
            int responded = (int) caseScores.stream().filter(CaseScore::receivedResponse).count();
            int malformed = (int) caseScores.stream()
                    .filter(score -> score.failureKind() == VisionHarnessScorer.FailureKind.FORMAT_ERROR)
                    .count();
            return new JsonCompliance(responded, malformed);
        }

        public double rate() {
            return responded == 0 ? 0.0 : (double) (responded - malformed) / responded;
        }
    }

    public String toText() {
        List<String> lines = new ArrayList<>();
        lines.add("=".repeat(96));
        lines.add("Vision 하네스 결과: " + label);
        lines.add("케이스 %d건 / 호출 실패 %d건 / 케이스당 평균 응답시간 %dms".formatted(
                caseCount, failureCount, averageLatencyMs));
        if (jsonCompliance != null && jsonCompliance.responded() > 0) {
            lines.add("JSON 준수율 %.0f%% (응답 %d건 중 형식 오류 %d건)".formatted(
                    jsonCompliance.rate() * 100, jsonCompliance.responded(), jsonCompliance.malformed()));
        }
        if (usage != null && usage.apiCalls() > 0) {
            lines.add("API 호출 %d회 (케이스당 %.1f회) / 토큰 %,d (입력 %,d + 출력 %,d), 케이스당 %,d".formatted(
                    usage.apiCalls(), caseCount == 0 ? 0.0 : (double) usage.apiCalls() / caseCount,
                    usage.totalTokens(), usage.promptTokens(), usage.completionTokens(),
                    caseCount == 0 ? 0 : usage.totalTokens() / caseCount));
        }
        lines.add("-".repeat(96));
        lines.add("%-16s %7s %5s %5s %6s %8s %8s %8s %8s".formatted(
                "필드", "채점대상", "정답", "근사", "오답", "기권", "응답률", "응답정확도", "전체정확도"));

        for (Field field : Field.values()) {
            FieldStat stat = fieldStats.get(field);
            lines.add("%-16s %7d %5d %5d %6d %8d %7.0f%% %9.0f%% %9.0f%%".formatted(
                    field.name(), stat.scored(), stat.correct(), stat.near(), stat.wrong(), stat.abstained(),
                    stat.fillRate() * 100, stat.precision() * 100, stat.accuracy() * 100));
        }

        lines.add("-".repeat(96));
        lines.add("케이스별 상세");
        for (CaseScore caseScore : caseScores) {
            String detail = caseScore.isFailure()
                    // 실패 종류를 같이 남긴다. API 오류는 다시 돌리면 되지만
                    // 형식 오류는 프롬프트나 스키마를 고쳐야 한다.
                    ? "%s: %s".formatted(
                            caseScore.failureKind() == VisionHarnessScorer.FailureKind.FORMAT_ERROR
                                    ? "형식 오류" : "호출 실패",
                            caseScore.failureMessage())
                    : formatOutcomes(caseScore);
            lines.add("  %-30s %6dms  %s".formatted(caseScore.caseId(), caseScore.latencyMs(), detail));
            // 틀린 필드는 실제로 뭐라고 답했는지 같이 남긴다. O/X만 있으면 원인을 알 수 없다.
            caseScore.mismatches().forEach((field, mismatch) ->
                    lines.add("        └ %-6s %s".formatted(shortName(field), mismatch)));
        }
        lines.add("=".repeat(96));
        return String.join(System.lineSeparator(), lines);
    }

    private String formatOutcomes(CaseScore caseScore) {
        List<String> parts = new ArrayList<>();
        for (Field field : Field.values()) {
            parts.add("%s=%s".formatted(shortName(field), symbol(caseScore.outcomes().get(field))));
        }
        return String.join(" ", parts);
    }

    private String shortName(Field field) {
        return switch (field) {
            case BRAND -> "brand";
            case MODEL_NAME -> "model";
            case SIZE -> "size";
            case BOX_INCLUDED -> "box";
            case CONDITION_GRADE -> "grade";
        };
    }

    private String symbol(Outcome outcome) {
        return switch (outcome) {
            case CORRECT -> "O";
            case NEAR -> "~";
            case WRONG -> "X";
            case ABSTAINED -> "-";
            case NOT_LABELED -> ".";
        };
    }
}
