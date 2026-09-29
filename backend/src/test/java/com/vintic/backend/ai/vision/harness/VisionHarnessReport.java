package com.vintic.backend.ai.vision.harness;

import com.vintic.backend.ai.vision.harness.VisionHarnessScorer.CaseScore;
import com.vintic.backend.ai.vision.harness.VisionHarnessScorer.Field;
import com.vintic.backend.ai.vision.harness.VisionHarnessScorer.Outcome;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
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
        JsonCompliance jsonCompliance,
        // 케이스 ID -> 그 케이스가 부른 호출들(호출 순서대로). 단계별 시간을 케이스마다 보려고 둔다.
        Map<String, List<Call>> callsByCase
) {

    // API 호출 한 번. stage는 silhouette/label/condition, V1은 single.
    // latencyMs에는 429 재시도 대기가 포함된다.
    public record Call(String stage, long latencyMs, int promptTokens, int completionTokens,
                       int imageCount, String detail) {
    }

    // 호출 비용을 재기 위한 집계. 정확도가 올라도 비용이 몇 배로 뛰면 채택할 수 없으므로 같이 본다.
    public record Usage(int apiCalls, int promptTokens, int completionTokens, List<StageUsage> stages) {

        public static final Usage EMPTY = new Usage(0, 0, 0, List.of());

        public static Usage from(List<Call> calls) {
            // 단계 순서는 처음 호출된 순서를 따른다(silhouette -> label -> condition).
            Map<String, List<Call>> byStage = new LinkedHashMap<>();
            calls.forEach(call -> byStage.computeIfAbsent(call.stage(), stage -> new ArrayList<>()).add(call));

            List<StageUsage> stages = byStage.entrySet().stream()
                    .map(entry -> StageUsage.from(entry.getKey(), entry.getValue()))
                    .toList();
            return new Usage(
                    calls.size(),
                    calls.stream().mapToInt(Call::promptTokens).sum(),
                    calls.stream().mapToInt(Call::completionTokens).sum(),
                    stages);
        }

        public int totalTokens() {
            return promptTokens + completionTokens;
        }

        public long totalLatencyMs() {
            return stages.stream().mapToLong(StageUsage::totalLatencyMs).sum();
        }
    }

    // 단계 하나의 집계. "어느 단계가 느린가"와 "그게 입력 때문인가 출력 때문인가"를 같이 보려고
    // 지연과 입력·출력 토큰을 나란히 둔다.
    public record StageUsage(String stage, int calls, long totalLatencyMs, long maxLatencyMs,
                             int promptTokens, int completionTokens) {

        static StageUsage from(String stage, List<Call> calls) {
            return new StageUsage(
                    stage,
                    calls.size(),
                    calls.stream().mapToLong(Call::latencyMs).sum(),
                    calls.stream().mapToLong(Call::latencyMs).max().orElse(0L),
                    calls.stream().mapToInt(Call::promptTokens).sum(),
                    calls.stream().mapToInt(Call::completionTokens).sum());
        }

        public long averageLatencyMs() {
            return calls == 0 ? 0L : Math.round((double) totalLatencyMs / calls);
        }

        public int averagePromptTokens() {
            return calls == 0 ? 0 : Math.round((float) promptTokens / calls);
        }

        public int averageCompletionTokens() {
            return calls == 0 ? 0 : Math.round((float) completionTokens / calls);
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
        return aggregate(label, caseScores, usage, Map.of());
    }

    public static VisionHarnessReport aggregate(String label, List<CaseScore> caseScores, Usage usage,
                                                Map<String, List<Call>> callsByCase) {
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
                fieldStats, caseScores, usage, JsonCompliance.from(caseScores), Map.copyOf(callsByCase));
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
        if (usage != null && !usage.stages().isEmpty()) {
            lines.add("-".repeat(96));
            lines.add("단계별 (호출 1회 평균, 지연에는 429 재시도 대기 포함, 비중 = 전체 호출 시간 중 이 단계 몫)");
            lines.add("%-12s %5s %10s %10s %9s %9s %7s".formatted(
                    "단계", "호출", "평균지연", "최대지연", "입력토큰", "출력토큰", "비중"));
            long totalLatencyMs = usage.totalLatencyMs();
            for (StageUsage stage : usage.stages()) {
                lines.add("%-12s %5d %,8dms %,8dms %,9d %,9d %6.0f%%".formatted(
                        stage.stage(), stage.calls(), stage.averageLatencyMs(), stage.maxLatencyMs(),
                        stage.averagePromptTokens(), stage.averageCompletionTokens(),
                        totalLatencyMs == 0 ? 0.0 : stage.totalLatencyMs() * 100.0 / totalLatencyMs));
            }
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
            // 평균만 보면 한 케이스의 이상치(재시도 대기 등)가 어느 단계에서 났는지 묻힌다.
            List<Call> calls = callsByCase == null ? List.of() : callsByCase.getOrDefault(caseScore.caseId(), List.of());
            if (!calls.isEmpty()) {
                lines.add("        └ 단계   " + formatCalls(calls));
            }
            // 틀린 필드는 실제로 뭐라고 답했는지 같이 남긴다. O/X만 있으면 원인을 알 수 없다.
            caseScore.mismatches().forEach((field, mismatch) ->
                    lines.add("        └ %-6s %s".formatted(shortName(field), mismatch)));
        }
        lines.add("=".repeat(96));
        return String.join(System.lineSeparator(), lines);
    }

    // 호출 단위 원자료. 리포트 표는 평균이라 "출력 토큰이 늘면 지연이 비례해 느는가" 같은 질문에
    // 답하지 못한다. 스프레드시트에서 바로 산점도를 그릴 수 있게 한 호출 한 줄로 남긴다.
    public String toCallsCsv() {
        List<String> lines = new ArrayList<>();
        lines.add("case_id,call_index,stage,latency_ms,prompt_tokens,completion_tokens,image_count,detail");
        for (CaseScore caseScore : caseScores) {
            List<Call> calls = callsByCase == null ? List.of() : callsByCase.getOrDefault(caseScore.caseId(), List.of());
            for (int i = 0; i < calls.size(); i++) {
                Call call = calls.get(i);
                lines.add("%s,%d,%s,%d,%d,%d,%d,%s".formatted(
                        caseScore.caseId(), i + 1, call.stage(), call.latencyMs(), call.promptTokens(),
                        call.completionTokens(), call.imageCount(), call.detail() == null ? "" : call.detail()));
            }
        }
        return String.join(System.lineSeparator(), lines) + System.lineSeparator();
    }

    private String formatCalls(List<Call> calls) {
        List<String> parts = new ArrayList<>();
        for (Call call : calls) {
            parts.add("%s %,dms(입력 %,d/출력 %,d)".formatted(
                    call.stage(), call.latencyMs(), call.promptTokens(), call.completionTokens()));
        }
        return String.join(" · ", parts);
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
            case COLOR -> "color";
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
