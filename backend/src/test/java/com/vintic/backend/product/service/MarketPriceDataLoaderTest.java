package com.vintic.backend.product.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MarketPriceDataLoaderTest {

    private final MarketPriceDataLoader loader = new MarketPriceDataLoader();

    private Integer parseSize(String raw) {
        return (Integer) ReflectionTestUtils.invokeMethod(loader, "parseSizeKr", raw);
    }

    @Test
    void 괄호로_해외_사이즈가_병기돼도_한국_사이즈만_읽는다() {
        // 숫자만 남기는 방식이면 20015가 된다. 값이 틀리는 게 아니라 그 행이
        // 어떤 요청과도 매칭되지 않아 조용히 없는 것처럼 동작한다.
        assertThat(parseSize("200(US 1.5)")).isEqualTo(200);
        assertThat(parseSize("190(13K)")).isEqualTo(190);
        assertThat(parseSize("210(US 2.5)")).isEqualTo(210);
    }

    @Test
    void 평범한_사이즈_표기는_그대로_읽는다() {
        assertThat(parseSize("270")).isEqualTo(270);
        assertThat(parseSize(" 285 ")).isEqualTo(285);
    }

    @Test
    void 신발_사이즈로_볼_수_없는_값은_버린다() {
        assertThat(parseSize("20015")).isNull();
        assertThat(parseSize("0")).isNull();
        assertThat(parseSize("999")).isNull();
    }

    @Test
    void 값이_없으면_null을_돌려준다() {
        assertThat(parseSize(null)).isNull();
        assertThat(parseSize("  ")).isNull();
        assertThat(parseSize("US 9")).isNull();
    }

    @Test
    void 실제_CSV의_모든_행이_유효한_사이즈를_갖는다() {
        // 사이즈가 깨진 행은 매칭이 안 돼 결과적으로 참조에서 빠진다.
        // 로딩 단계에서 걸러지는지 확인한다.
        List<MarketPriceDataLoader.MarketPriceRow> rows = loader.loadKreamRows();

        assertThat(rows).isNotEmpty();
        assertThat(rows).allSatisfy(row ->
                assertThat(row.sizeKr()).isBetween(150, 350));
    }

    @Test
    void eBay_CSV도_유효한_사이즈만_남는다() {
        List<MarketPriceDataLoader.MarketPriceRow> rows = loader.loadEbayRows();

        assertThat(rows).isNotEmpty();
        assertThat(rows).allSatisfy(row ->
                assertThat(row.sizeKr()).isBetween(150, 350));
    }
}
