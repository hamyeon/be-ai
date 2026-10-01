package com.vintic.backend.recommendation.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductVectorTest {

    @Test
    void 벡터를_바이트로_저장했다_그대로_복원한다() {
        float[] original = {0.1f, -0.25f, 0.0f, 1.5f};

        ProductVector vector = ProductVector.of(1L, original, "Nike Dunk Low");

        assertThat(vector.toVector()).containsExactly(original);
        assertThat(vector.getDimension()).isEqualTo(4);
    }

    @Test
    void 입력_텍스트가_같으면_최신_상태다() {
        // 임베딩 호출은 유료라 같은 텍스트면 다시 부르지 않아야 한다
        ProductVector vector = ProductVector.of(1L, new float[]{0.1f}, "Nike Dunk Low");

        assertThat(vector.isStale("Nike Dunk Low")).isFalse();
        assertThat(vector.isStale("Nike Dunk High")).isTrue();
    }

    @Test
    void 빈_벡터는_거부한다() {
        assertThatThrownBy(() -> ProductVector.of(1L, new float[0], "text"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProductVector.of(1L, null, "text"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 상품_id는_필수다() {
        assertThatThrownBy(() -> ProductVector.of(null, new float[]{0.1f}, "text"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
