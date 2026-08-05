package com.vintic.backend.ai.vision.harness;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VisionHarnessFixturesTest {

    @Test
    void 픽스처가_로딩되고_케이스마다_이미지와_정답이_있다() {
        VisionHarnessFixtures.Document document = VisionHarnessFixtures.load();

        assertThat(document.cases()).isNotEmpty();
        assertThat(document.cases()).allSatisfy(harnessCase -> {
            assertThat(harnessCase.id()).isNotBlank();
            assertThat(harnessCase.imageBaseUrls()).isNotEmpty();
            assertThat(harnessCase.expected().brand()).isNotEmpty();
            assertThat(harnessCase.expected().size()).isNotNull();
            // 원본 URL만 담아야 한다. 쿼리가 붙어 있으면 해상도 변형 비교가 무의미해진다.
            assertThat(harnessCase.imageBaseUrls()).allSatisfy(url -> assertThat(url).doesNotContain("?"));
        });
    }

    @Test
    void 케이스_id는_중복되지_않는다() {
        List<String> ids = VisionHarnessFixtures.load().cases().stream().map(VisionHarnessCase::id).toList();

        assertThat(ids).doesNotHaveDuplicates();
    }

    @Test
    void 이미지_변형은_해상도_쿼리를_붙여준다() {
        List<String> baseUrls = List.of("https://example.com/a.webp");

        assertThat(VisionHarnessImageVariant.ORIGIN.apply(baseUrls)).containsExactly("https://example.com/a.webp");
        assertThat(VisionHarnessImageVariant.THUMBNAIL_300.apply(baseUrls))
                .containsExactly("https://example.com/a.webp?q=82&s=300x300&t=crop&service=webapp&f=webp");
    }
}
