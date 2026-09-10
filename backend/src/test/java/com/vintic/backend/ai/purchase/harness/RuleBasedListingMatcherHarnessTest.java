package com.vintic.backend.ai.purchase.harness;

import com.vintic.backend.ai.purchase.match.MatchResult;
import com.vintic.backend.ai.purchase.match.RuleBasedListingMatcher;
import com.vintic.backend.ai.purchase.model.ModelAliases;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// 규칙 기반 Matcher의 기준선. API 없이 매 빌드에서 돈다.
//
// 하한은 최초 측정값에서 여유를 뺀 회귀 방지선이다. 거짓 양성 상한을 따로 두는 이유는
// 잘못된 matched=true가 잘못된 AutoBid로 이어지기 때문이다 - 정확도가 같아도 거짓 양성이
// 늘면 실패해야 한다.
class RuleBasedListingMatcherHarnessTest {

    private static final Path REPORT_DIRECTORY = Path.of("build", "goal-parse-harness");

    private static final double ACCURACY_FLOOR = 0.85;
    private static final int FALSE_POSITIVE_CEILING = 3;

    @Test
    void 픽스처_전체를_돌려_정확도와_거짓_양성을_측정하고_기준선을_지킨다() throws IOException {
        ListingMatchHarnessFixtures.Document fixtures = ListingMatchHarnessFixtures.load();
        RuleBasedListingMatcher matcher = new RuleBasedListingMatcher(new ModelAliases());

        List<ListingMatchHarnessReport.CaseScore> scores = new ArrayList<>();
        long auctionId = 1;
        for (ListingMatchHarnessCase harnessCase : fixtures.cases()) {
            long startedAt = System.nanoTime();
            try {
                MatchResult result = matcher.evaluate(
                        harnessCase.goal().toMatchGoal(), harnessCase.listing().toAuctionListing(auctionId++));
                scores.add(ListingMatchHarnessReport.CaseScore.of(harnessCase, result, elapsedMs(startedAt)));
            } catch (RuntimeException e) {
                scores.add(ListingMatchHarnessReport.CaseScore.failed(harnessCase, elapsedMs(startedAt), e));
            }
        }

        ListingMatchHarnessReport report = ListingMatchHarnessReport.aggregate(
                "matcher=rule, fixtures=" + fixtures.version(), scores, ListingMatchHarnessReport.Usage.NONE);
        System.out.println(report.toText());
        Files.createDirectories(REPORT_DIRECTORY);
        Path reportPath = REPORT_DIRECTORY.resolve("listing-match-rule.txt");
        Files.writeString(reportPath, report.toText(), StandardCharsets.UTF_8);
        System.out.println("리포트 저장: " + reportPath.toAbsolutePath());

        assertThat(report.failureCount()).isZero();
        assertThat(report.accuracy()).isGreaterThanOrEqualTo(ACCURACY_FLOOR);
        assertThat(report.falsePositives()).isLessThanOrEqualTo(FALSE_POSITIVE_CEILING);
    }

    private long elapsedMs(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }
}
