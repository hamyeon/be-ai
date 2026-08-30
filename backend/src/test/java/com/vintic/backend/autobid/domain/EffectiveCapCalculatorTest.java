package com.vintic.backend.autobid.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// §0.13 effectiveCap 공식(ProxyPriceEngine과 AutoBidSetting.getEffectiveCap()이 공유하는 유일한
// 계산 지점)을 독립적으로 고정한다. floor 동작 자체를 이 계산식 밖(engine/entity)에서 검증하면
// 매번 currentPrice/bidIncrement 조합을 다시 세팅해야 해서 결합도가 높아진다 - 여기서 순수하게
// 공식만 고정해둔다.
class EffectiveCapCalculatorTest {

    @Test
    void maxAmount가_이미_배수로_정렬돼_있으면_effectiveCap은_maxAmount와_같다() {
        long effectiveCap = EffectiveCapCalculator.calculate(120000L, 105000L, 5000L);

        assertThat(effectiveCap).isEqualTo(120000L);
    }

    @Test
    void maxAmount가_배수가_아니면_도달_가능한_마지막_grid_지점으로_내림한다() {
        // (121000-105000)/5000 = 3.2 -> floor 3 -> 105000 + 15000 = 120000
        long effectiveCap = EffectiveCapCalculator.calculate(121000L, 105000L, 5000L);

        assertThat(effectiveCap).isEqualTo(120000L);
    }

    @Test
    void maxAmount가_currentPrice_bidIncrement_바로_다음_한_단계보다_작으면_currentPrice_그대로다() {
        // (109999-105000)/5000 = 0.9999 -> floor 0 -> 한 단계도 못 오른다
        long effectiveCap = EffectiveCapCalculator.calculate(109999L, 105000L, 5000L);

        assertThat(effectiveCap).isEqualTo(105000L);
    }

    @Test
    void maxAmount가_currentPrice와_같으면_effectiveCap도_currentPrice와_같다() {
        long effectiveCap = EffectiveCapCalculator.calculate(105000L, 105000L, 5000L);

        assertThat(effectiveCap).isEqualTo(105000L);
    }

    @Test
    void effectiveCap은_maxAmount를_절대_초과하지_않는다() {
        long[] maxAmounts = {105001L, 109999L, 121000L, 999999L, 105000L, 200000L};
        for (long maxAmount : maxAmounts) {
            long effectiveCap = EffectiveCapCalculator.calculate(maxAmount, 105000L, 5000L);
            assertThat(effectiveCap).isLessThanOrEqualTo(maxAmount);
        }
    }

    @Test
    void effectiveCap은_currentPrice_bidIncrement_배수_그리드_위에만_존재한다() {
        long[] maxAmounts = {105001L, 109999L, 121000L, 999999L};
        for (long maxAmount : maxAmounts) {
            long effectiveCap = EffectiveCapCalculator.calculate(maxAmount, 105000L, 5000L);
            assertThat((effectiveCap - 105000L) % 5000L).isZero();
        }
    }

    @Test
    void bidIncrement이_1이어도_정상_계산된다() {
        long effectiveCap = EffectiveCapCalculator.calculate(100005L, 100000L, 1L);

        assertThat(effectiveCap).isEqualTo(100005L);
    }
}
