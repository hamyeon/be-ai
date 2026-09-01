package com.vintic.backend.product.service;

import com.vintic.backend.product.service.ConditionRateProvider.Basis;
import com.vintic.backend.product.service.ConditionRateProvider.ConditionRate;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConditionRateProviderTest {

    private final ConditionRateProvider provider = new ConditionRateProvider();

    @Test
    void 모델별_실측값이_있으면_그것을_쓴다() {
        ConditionRate rate = provider.resolve("Air Force 1 Low", "UNKNOWN");

        assertThat(rate.basis()).isEqualTo(Basis.MEASURED_MODEL);
        assertThat(rate.sampleSize()).isGreaterThan(0);
    }

    @Test
    void 감가가_느린_모델은_더_높은_계수를_받는다() {
        // 에어포스1은 상시 대량 생산이라 중고가 흔하고, 993은 물량이 적어 시세가 유지된다.
        // 통합 계수 하나로 뭉개면 993이 30% 가까이 낮게 추천된다.
        ConditionRate airForce = provider.resolve("Air Force 1 Low", "UNKNOWN");
        ConditionRate nb993 = provider.resolve("993", "UNKNOWN");

        assertThat(nb993.basis()).isEqualTo(Basis.MEASURED_MODEL);
        assertThat(nb993.rate()).isGreaterThan(airForce.rate());
    }

    @Test
    void 모델_실측값이_없으면_공통_실측값으로_떨어진다() {
        // Dunk Low는 참조 컬러웨이가 한정판이라 모델별 계수를 내지 않았다.
        ConditionRate rate = provider.resolve("Dunk Low", "UNKNOWN");

        assertThat(rate.basis()).isEqualTo(Basis.MEASURED_COMMON);
        assertThat(rate.rate()).isLessThan(0.60);
    }

    @Test
    void 공통_실측값도_없으면_기존_기본값을_유지한다() {
        // S는 표본이 12건뿐이라 CSV에 넣지 않았다. 12건짜리 중앙값으로 계수를 바꾸면
        // 근거 없는 값을 근거 없는 값으로 바꾸는 것뿐이다.
        ConditionRate rate = provider.resolve("Dunk Low", "S");

        assertThat(rate.basis()).isEqualTo(Basis.DEFAULT);
        assertThat(rate.rate()).isEqualTo(0.70);
        assertThat(rate.sampleSize()).isZero();
    }

    @Test
    void 실측하지_않은_등급들은_그대로다() {
        assertThat(provider.resolve("Dunk Low", "A").rate()).isEqualTo(0.60);
        assertThat(provider.resolve("Dunk Low", "B").rate()).isEqualTo(0.40);
        assertThat(provider.resolve("Dunk Low", "C").rate()).isEqualTo(0.20);
    }

    @Test
    void 모델명_표기가_달라도_같은_계수를_찾는다() {
        // CSV는 "airforce1low"인데 요청은 "Air Force 1"처럼 들어올 수 있다.
        assertThat(provider.resolve("Air Force 1", "UNKNOWN").basis())
                .isEqualTo(Basis.MEASURED_MODEL);
        assertThat(provider.resolve("AIRFORCE1LOW", "UNKNOWN").basis())
                .isEqualTo(Basis.MEASURED_MODEL);
    }

    @Test
    void 등급이_없으면_상태_미상으로_본다() {
        assertThat(provider.resolve("Dunk Low", null).rate())
                .isEqualTo(provider.resolve("Dunk Low", "UNKNOWN").rate());
        assertThat(provider.resolve("Dunk Low", "  ").rate())
                .isEqualTo(provider.resolve("Dunk Low", "UNKNOWN").rate());
    }

    @Test
    void 모르는_모델과_등급도_값을_돌려준다() {
        // 가격 계산이 멈추면 안 된다.
        ConditionRate rate = provider.resolve("존재하지 않는 모델", "Z");

        assertThat(rate.rate()).isEqualTo(0.60);
        assertThat(rate.basis()).isEqualTo(Basis.DEFAULT);
    }

    @Test
    void 모델명이_없어도_공통값으로_동작한다() {
        ConditionRate rate = provider.resolve(null, "UNKNOWN");

        assertThat(rate.basis()).isEqualTo(Basis.MEASURED_COMMON);
    }
}
