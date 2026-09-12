package com.vintic.backend.ai.purchase.harness;

import com.vintic.backend.ai.purchase.model.ModelAliases;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// Matcher 픽스처 정합성.
class ListingMatchFixturesTest {

    private final ModelAliases modelAliases = new ModelAliases();

    @Test
    void 픽스처가_로딩되고_케이스마다_goal과_listing_기대값이_있다() {
        ListingMatchHarnessFixtures.Document document = ListingMatchHarnessFixtures.load();

        assertThat(document.cases()).hasSizeGreaterThanOrEqualTo(50);
        assertThat(document.cases()).allSatisfy(harnessCase -> {
            assertThat(harnessCase.id()).isNotBlank();
            assertThat(harnessCase.goal()).isNotNull();
            assertThat(harnessCase.listing()).isNotNull();
            assertThat(harnessCase.listing().title()).isNotBlank();
            assertThat(harnessCase.expected()).isNotNull();
        });
    }

    @Test
    void 케이스_id는_중복되지_않는다() {
        List<String> ids = ListingMatchHarnessFixtures.load().cases().stream().map(ListingMatchHarnessCase::id).toList();

        assertThat(ids).doesNotHaveDuplicates();
    }

    @Test
    void goal의_modelKey는_전부_카탈로그에_있다() {
        assertThat(ListingMatchHarnessFixtures.load().cases()).allSatisfy(harnessCase -> {
            String modelKey = harnessCase.goal().modelKey();
            if (modelKey != null) {
                assertThat(modelAliases.isKnownKey(modelKey))
                        .as("%s: 카탈로그에 없는 modelKey %s", harnessCase.id(), modelKey).isTrue();
            }
        });
    }

    @Test
    void 일치와_불일치_케이스가_고르게_있다() {
        // 한쪽만 있으면 "전부 true"나 "전부 false"로 답하는 판정기도 높은 점수를 받는다.
        List<ListingMatchHarnessCase> cases = ListingMatchHarnessFixtures.load().cases();
        long positives = cases.stream().filter(c -> c.expected().matched()).count();

        assertThat(positives).isBetween(cases.size() / 3L, cases.size() * 2L / 3);
    }
}
