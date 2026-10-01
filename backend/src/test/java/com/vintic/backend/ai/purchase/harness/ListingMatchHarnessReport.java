package com.vintic.backend.ai.purchase.harness;

import com.vintic.backend.ai.purchase.match.MatchResult;

import java.util.List;

// Matcher 하네스 채점 + 리포트. matched 이진 판정이라 정확도·거짓 양성·거짓 음성을 따로 센다.
//
// 거짓 양성(사지 말아야 할 매물을 후보로 올림)이 거짓 음성(살 만한 매물을 놓침)보다 비싸다.
// 잘못된 matched=true는 잘못된 AutoBid로 이어지고, 놓친 매물은 다음 scan에 다른 후보가 있다.
final class ListingMatchHarnessReport {

    record CaseScore(
            String id,
            boolean expected,
            Boolean actual,
            double semanticScore,
            String reason,
            long latencyMs,
            String failure,
            ListingMatchHarnessCase harnessCase
    ) {
        boolean failed() {
            return failure != null;
        }

        boolean correct() {
            return actual != null && actual == expected;
        }

        boolean falsePositive() {
            return actual != null && actual && !expected;
        }

        boolean falseNegative() {
            return actual != null && !actual && expected;
        }

        static CaseScore of(ListingMatchHarnessCase harnessCase, MatchResult result, long latencyMs) {
            return new CaseScore(harnessCase.id(), harnessCase.expected().matched(), result.matched(),
                    result.semanticScore(), result.reason(), latencyMs, null, harnessCase);
        }

        static CaseScore failed(ListingMatchHarnessCase harnessCase, long latencyMs, RuntimeException e) {
            return new CaseScore(harnessCase.id(), harnessCase.expected().matched(), null, 0.0, null,
                    latencyMs, e.getClass().getSimpleName() + ": " + e.getMessage(), harnessCase);
        }
    }

    record Usage(int apiCalls, int promptTokens, int completionTokens) {
        static final Usage NONE = new Usage(0, 0, 0);
    }

    private final String label;
    private final List<CaseScore> scores;
    private final Usage usage;

    private ListingMatchHarnessReport(String label, List<CaseScore> scores, Usage usage) {
        this.label = label;
        this.scores = scores;
        this.usage = usage;
    }

    static ListingMatchHarnessReport aggregate(String label, List<CaseScore> scores, Usage usage) {
        return new ListingMatchHarnessReport(label, List.copyOf(scores), usage);
    }

    double accuracy() {
        return scores.isEmpty() ? 0.0 : (double) scores.stream().filter(CaseScore::correct).count() / scores.size();
    }

    int falsePositives() {
        return (int) scores.stream().filter(CaseScore::falsePositive).count();
    }

    int falseNegatives() {
        return (int) scores.stream().filter(CaseScore::falseNegative).count();
    }

    int failureCount() {
        return (int) scores.stream().filter(CaseScore::failed).count();
    }

    String toText() {
        StringBuilder out = new StringBuilder();
        int total = scores.size();
        long positives = scores.stream().filter(s -> s.expected()).count();
        out.append("=== 매물 적합도 하네스: ").append(label).append(" ===\n");
        out.append("케이스 %d건 (정답 일치 %d / 불일치 %d), 실패(예외) %d건%n".formatted(
                total, positives, total - positives, failureCount()));
        out.append("정확도        %d/%d (%.0f%%)%n".formatted(
                scores.stream().filter(CaseScore::correct).count(), total, 100 * accuracy()));
        out.append("거짓 양성      %d건 (사지 말아야 할 매물을 일치로 판정)%n".formatted(falsePositives()));
        out.append("거짓 음성      %d건 (살 만한 매물을 놓침)%n".formatted(falseNegatives()));
        out.append("지연 평균 %.0fms, 최대 %dms%n".formatted(
                scores.stream().mapToLong(CaseScore::latencyMs).average().orElse(0),
                scores.stream().mapToLong(CaseScore::latencyMs).max().orElse(0)));
        if (usage.apiCalls() > 0) {
            out.append("API 호출 %d회, prompt %d / completion %d 토큰 (케이스당 %.0f)%n".formatted(
                    usage.apiCalls(), usage.promptTokens(), usage.completionTokens(),
                    (double) (usage.promptTokens() + usage.completionTokens()) / usage.apiCalls()));
        }
        out.append("\n--- 오답·실패 케이스 ---\n");
        for (CaseScore score : scores) {
            if (score.correct()) {
                continue;
            }
            out.append("[").append(score.id()).append("] ").append(score.harnessCase().listing().title()).append('\n');
            if (score.failed()) {
                out.append("    실패: ").append(score.failure()).append('\n');
                continue;
            }
            out.append("    기대 %s / 실제 %s - %s%n".formatted(
                    score.expected() ? "일치" : "불일치", score.actual() ? "일치" : "불일치", score.reason()));
            if (score.harnessCase().note() != null && !score.harnessCase().note().isBlank()) {
                out.append("    참고: ").append(score.harnessCase().note()).append('\n');
            }
        }
        return out.toString();
    }
}
