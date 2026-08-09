package com.vintic.backend.ai.vision.harness;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VisionHarnessFixturesTest {

    @ParameterizedTest
    @ValueSource(strings = {
            VisionHarnessFixtures.DAANGN, VisionHarnessFixtures.DAANGN_MULTI, VisionHarnessFixtures.FRUITSFAMILY})
    void 픽스처가_로딩되고_케이스마다_이미지와_정답이_있다(String setName) {
        VisionHarnessFixtures.Document document = VisionHarnessFixtures.load(setName);

        assertThat(document.cases()).isNotEmpty();
        assertThat(document.cases()).allSatisfy(harnessCase -> {
            assertThat(harnessCase.id()).isNotBlank();
            assertThat(harnessCase.imageBaseUrls()).isNotEmpty();
            assertThat(harnessCase.expected().brand()).isNotEmpty();
            assertThat(harnessCase.expected().size()).isNotNull();
            assertThat(harnessCase.groundTruthSource()).isNotBlank();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            VisionHarnessFixtures.DAANGN, VisionHarnessFixtures.DAANGN_MULTI, VisionHarnessFixtures.FRUITSFAMILY})
    void 케이스_id는_중복되지_않는다(String setName) {
        List<String> ids = VisionHarnessFixtures.load(setName).cases().stream().map(VisionHarnessCase::id).toList();

        assertThat(ids).doesNotHaveDuplicates();
    }

    @Test
    void 당근_셋은_원본_URL만_담아_해상도_변형이_가능하다() {
        VisionHarnessFixtures.Document document = VisionHarnessFixtures.load(VisionHarnessFixtures.DAANGN);

        assertThat(document.allowsImageVariants()).isTrue();
        // 쿼리가 붙어 있으면 해상도 변형 비교가 무의미해진다.
        assertThat(document.cases()).allSatisfy(harnessCase ->
                assertThat(harnessCase.imageBaseUrls()).allSatisfy(url -> assertThat(url).doesNotContain("?")));
    }

    @Test
    void 당근_다중_셋만_원본_해상도와_여러_장을_동시에_만족한다() {
        // detail low/high 비교는 이 조건에서만 의미가 있다.
        // daangn은 1장이라 라벨이 안 보이고, fruitsfamily는 620px 고정이라 해상도를 못 바꾼다.
        VisionHarnessFixtures.Document document = VisionHarnessFixtures.load(VisionHarnessFixtures.DAANGN_MULTI);

        assertThat(document.allowsImageVariants()).isTrue();
        assertThat(document.cases()).allSatisfy(harnessCase -> {
            assertThat(harnessCase.imageBaseUrls()).hasSizeGreaterThan(1);
            assertThat(harnessCase.imageBaseUrls()).allSatisfy(url -> assertThat(url).doesNotContain("?"));
        });
    }

    @Test
    void 후르츠패밀리_셋은_이미지가_여러_장이고_해상도_변형을_지원하지_않는다() {
        VisionHarnessFixtures.Document document = VisionHarnessFixtures.load(VisionHarnessFixtures.FRUITSFAMILY);

        // 사이즈 라벨이 찍힌 사진이 섞여 있어야 사이즈 판독을 측정할 수 있다.
        // 이미지가 한 장뿐이면 당근 셋과 다를 게 없다.
        assertThat(document.cases()).allSatisfy(harnessCase ->
                assertThat(harnessCase.imageBaseUrls()).hasSizeGreaterThan(1));
        // URL 경로에 리사이즈 규격이 박혀 있고 다른 규격은 403이라 변형이 불가능하다.
        assertThat(document.allowsImageVariants()).isFalse();
    }

    @Test
    void 이미지_변형은_해상도_쿼리를_붙여준다() {
        List<String> baseUrls = List.of("https://example.com/a.webp");

        assertThat(VisionHarnessImageVariant.ORIGIN.apply(baseUrls)).containsExactly("https://example.com/a.webp");
        assertThat(VisionHarnessImageVariant.THUMBNAIL_300.apply(baseUrls))
                .containsExactly("https://example.com/a.webp?q=82&s=300x300&t=crop&service=webapp&f=webp");
    }
}
