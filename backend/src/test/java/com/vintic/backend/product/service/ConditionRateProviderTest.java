package com.vintic.backend.product.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConditionRateProviderTest {

    private final ConditionRateProvider provider = new ConditionRateProvider();

    @Test
    void 실측_계수가_있는_등급은_실측값을_쓴다() {
        // condition_rates.csv에 DS와 UNKNOWN이 들어 있다.
        assertThat(provider.isMeasured("DS")).isTrue();
        assertThat(provider.rateOf("DS")).isNotEqualTo(0.80);
        assertThat(provider.sampleSizeOf("DS")).isGreaterThan(0);
    }

    @Test
    void 가장_흔한_상태_미상은_실측값으로_낮아진다() {
        // 기존 기본값 0.60은 새제품 시세에 임의 계수를 곱한 값이었다.
        // 실측 결과 일반 중고는 정가의 0.4배 근처였다.
        assertThat(provider.isMeasured("UNKNOWN")).isTrue();
        assertThat(provider.rateOf("UNKNOWN")).isLessThan(0.60);
    }

    @Test
    void 표본이_부족한_등급은_기존_기본값을_유지한다() {
        // S는 표본이 12건뿐이라 CSV에 넣지 않았다. 12건짜리 중앙값으로 계수를 바꾸면
        // 근거 없는 값을 근거 없는 값으로 바꾸는 것뿐이다.
        assertThat(provider.isMeasured("S")).isFalse();
        assertThat(provider.rateOf("S")).isEqualTo(0.70);
        assertThat(provider.sampleSizeOf("S")).isZero();
    }

    @Test
    void 실측하지_않은_등급들은_그대로다() {
        assertThat(provider.rateOf("A")).isEqualTo(0.60);
        assertThat(provider.rateOf("B")).isEqualTo(0.40);
        assertThat(provider.rateOf("C")).isEqualTo(0.20);
    }

    @Test
    void 등급_표기가_흔들려도_같은_계수를_돌려준다() {
        assertThat(provider.rateOf("ds")).isEqualTo(provider.rateOf("DS"));
        assertThat(provider.rateOf(" B ")).isEqualTo(provider.rateOf("B"));
    }

    @Test
    void 등급이_없으면_상태_미상으로_본다() {
        assertThat(provider.rateOf(null)).isEqualTo(provider.rateOf("UNKNOWN"));
        assertThat(provider.rateOf("  ")).isEqualTo(provider.rateOf("UNKNOWN"));
    }

    @Test
    void 정의되지_않은_등급도_값을_돌려준다() {
        // 가격 계산이 멈추면 안 된다.
        assertThat(provider.rateOf("Z")).isEqualTo(0.60);
        assertThat(provider.isMeasured("Z")).isFalse();
    }
}
