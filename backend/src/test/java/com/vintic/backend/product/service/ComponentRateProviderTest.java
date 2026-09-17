package com.vintic.backend.product.service;

import com.vintic.backend.product.service.ComponentRateProvider.Basis;
import com.vintic.backend.product.service.ComponentRateProvider.ComponentRate;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ComponentRateProviderTest {

    private final ComponentRateProvider provider = new ComponentRateProvider();

    @Test
    void 실측된_상태는_실측값과_표본수를_밝힌다() {
        ComponentRate none = provider.resolve("NONE");

        assertThat(none.basis()).isEqualTo(Basis.MEASURED);
        assertThat(none.rate()).isEqualTo(0.9);
        assertThat(none.sampleSize()).isGreaterThan(0);
    }

    @Test
    void 구성품이_없으면_전체_매물보다_싸다() {
        // 계수는 "구성품 불문 전체 대비 몇 배"다. 1보다 작아야 한다.
        assertThat(provider.resolve("NONE").rate()).isLessThan(1.0);
    }

    @Test
    void FULL은_셀별_편차가_커_기본값을_유지한다() {
        // 표본은 26셀 778건으로 충분했지만 셀별 값이 0.76~2.27로 흩어졌다(상대IQR 0.394).
        // 중앙값 1.379를 쓰면 풀박스 추천가가 38% 오르는데 그 숫자를 설명할 수 없다.
        ComponentRate full = provider.resolve("FULL");

        assertThat(full.basis()).isEqualTo(Basis.DEFAULT);
        assertThat(full.rate()).isEqualTo(1.00);
        assertThat(full.sampleSize()).isZero();
    }

    @Test
    void 정의되지_않은_값과_null은_미상으로_본다() {
        // 임의 문자열이 와도 계산은 돌아야 한다
        assertThat(provider.resolve(null).rate()).isEqualTo(provider.resolve("UNKNOWN").rate());
        assertThat(provider.resolve("  ").rate()).isEqualTo(provider.resolve("UNKNOWN").rate());
        assertThat(provider.resolve("박스있음").rate()).isEqualTo(provider.resolve("UNKNOWN").rate());
    }

    @Test
    void 소문자로_와도_같은_계수를_준다() {
        assertThat(provider.resolve("none").rate()).isEqualTo(provider.resolve("NONE").rate());
    }

    @Test
    void 서열이_지켜진다() {
        assertThat(provider.resolve("FULL").rate())
                .isGreaterThanOrEqualTo(provider.resolve("PARTIAL").rate());
        assertThat(provider.resolve("PARTIAL").rate())
                .isGreaterThan(provider.resolve("NONE").rate());
    }

    @Test
    void FULL_대비_환산은_기준이_없으면_기본값으로_떨어진다() {
        // KREAM 경로용. FULL이 실측되지 않은 지금은 환산 기준이 없으므로,
        // 모집단이 어긋난 계수를 쓰느니 풀박스 기준으로 정해진 기본값을 쓴다.
        ComponentRate none = provider.resolveAgainstFull("NONE");

        assertThat(none.basis()).isEqualTo(Basis.DEFAULT);
        assertThat(none.rate()).isEqualTo(0.95);
    }

    @Test
    void FULL_대비_환산에서_풀박스는_기준가를_그대로_받는다() {
        assertThat(provider.resolveAgainstFull("FULL").rate()).isEqualTo(1.00);
    }
}
