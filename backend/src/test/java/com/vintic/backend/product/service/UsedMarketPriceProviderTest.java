package com.vintic.backend.product.service;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

// 실제 used_market_prices.csv를 읽어 검증한다. 이 파일은 크롤링 산출물이라
// 재생성 시 값이 바뀔 수 있으므로, 구체적 가격이 아니라 구조적 성질만 확인한다.
class UsedMarketPriceProviderTest {

    private final UsedMarketPriceProvider provider = new UsedMarketPriceProvider();

    @Test
    void 브랜드와_모델이_맞으면_중고_시세를_찾는다() {
        Optional<UsedMarketPriceProvider.UsedMarketPrice> found =
                provider.find("Nike", "Dunk Low");

        assertThat(found).isPresent();
        assertThat(found.get().listingCount()).isGreaterThanOrEqualTo(10);
        assertThat(found.get().medianPrice()).isPositive();
        // 분포가 뒤집혀 있으면 산출이 잘못된 것이다
        assertThat(found.get().q1Price()).isLessThanOrEqualTo(found.get().medianPrice());
        assertThat(found.get().q3Price()).isGreaterThanOrEqualTo(found.get().medianPrice());
    }

    @Test
    void 모델_표기가_흔들려도_찾는다() {
        // Vision이 "Jordan 1 Retro High"처럼 길게 답해도 jordan1 시세에 붙어야 한다
        assertThat(provider.find("Nike", "Jordan 1 Retro High")).isPresent();
        assertThat(provider.find("NIKE", "AIR FORCE 1")).isPresent();
    }

    @Test
    void 브랜드가_다르면_모델이_같아도_매칭하지_않는다() {
        // "574" 같은 숫자 모델이 다른 브랜드 요청에 붙으면 안 된다
        assertThat(provider.find("New Balance", "574")).isPresent();
        assertThat(provider.find("Adidas", "574")).isEmpty();
    }

    @Test
    void 시세에_없는_모델이면_empty를_돌려준다() {
        // 호출부는 이 empty를 보고 기존 KREAM/eBay 방식으로 폴백한다
        assertThat(provider.find("Nike", "존재하지 않는 모델")).isEmpty();
    }

    @Test
    void 입력이_비어도_안전하다() {
        assertThat(provider.find(null, "Dunk Low")).isEmpty();
        assertThat(provider.find("Nike", null)).isEmpty();
        assertThat(provider.find("  ", "  ")).isEmpty();
    }
}
