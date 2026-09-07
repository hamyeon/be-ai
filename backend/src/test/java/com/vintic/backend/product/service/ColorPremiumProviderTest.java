package com.vintic.backend.product.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// 실제 kream_color_premiums.csv를 읽어 검증한다. 크롤링 산출물이라 재생성 시
// 값이 바뀔 수 있으므로 구조적 성질만 확인한다.
class ColorPremiumProviderTest {

    private final ColorPremiumProvider provider = new ColorPremiumProvider();

    @Test
    void 색상_표기가_달라도_같은_프리미엄을_찾는다() {
        var english = provider.find("Dr. Martens", "1461", "Brown");
        var korean = provider.find("Dr. Martens", "1461", "브라운");

        assertThat(english).isPresent();
        assertThat(korean).isPresent();
        assertThat(english.get().premium()).isEqualTo(korean.get().premium());
        // 프리미엄은 정상 범위 안이어야 한다 - 벗어나면 로드에서 걸러진다
        assertThat(english.get().premium()).isBetween(0.5, 2.0);
        assertThat(english.get().tradeCount()).isGreaterThanOrEqualTo(5);
    }

    @Test
    void 프리미엄이_없는_조합은_empty다() {
        assertThat(provider.find("Dr. Martens", "1461", "Purple")).isEmpty();
        assertThat(provider.find("Nike", "1461", "Brown")).isEmpty();
        assertThat(provider.find("Dr. Martens", "1461", null)).isEmpty();
    }
}
