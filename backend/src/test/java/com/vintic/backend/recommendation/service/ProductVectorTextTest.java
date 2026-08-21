package com.vintic.backend.recommendation.service;

import com.vintic.backend.product.domain.Product;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProductVectorTextTest {

    private Product product(String brand, String model, String colorway, Integer size,
                            String grade, String component, Integer price, String description) {
        return new Product(null, List.of("https://example.com/a.jpg"), brand, model, colorway, size,
                grade, component, price, price, null, null, null, description);
    }

    @Test
    void 구조화_필드를_사람이_읽는_텍스트로_만든다() {
        String text = ProductVectorText.of(
                product("Nike", "Dunk Low", "Panda", 270, "A", "FULL", 180000, null));

        assertThat(text).contains("Nike", "Dunk Low", "Panda", "270mm");
        assertThat(text).contains("상태 좋은 중고");   // 등급 문자만으론 임베딩이 의미를 모른다
        assertThat(text).contains("구성품 모두 포함");
    }

    @Test
    void 판매글_설명은_넣지_않는다() {
        // "네고 사절" 같은 문구는 취향과 무관해 벡터를 흐린다
        String text = ProductVectorText.of(
                product("Nike", "Dunk Low", "Panda", 270, "A", "FULL", 180000,
                        "네고 사절 직거래만 급처합니다"));

        assertThat(text).doesNotContain("네고", "직거래", "급처");
    }

    @Test
    void 가격은_구간으로_넣는다() {
        // 179,000과 181,000은 취향 관점에서 같은 가격대다
        String near = ProductVectorText.of(product("Nike", "Dunk", "Panda", 270, "A", "FULL", 179000, null));
        String alsoNear = ProductVectorText.of(product("Nike", "Dunk", "Panda", 270, "A", "FULL", 181000, null));

        assertThat(near).contains("10만원대");
        assertThat(alsoNear).contains("10만원대");
        assertThat(near).isEqualTo(alsoNear);
    }

    @Test
    void 십만원_미만은_따로_표기한다() {
        String text = ProductVectorText.of(product("Nike", "Dunk", "Panda", 270, "A", "FULL", 50000, null));

        assertThat(text).contains("10만원 미만");
    }

    @Test
    void 비어_있는_필드는_건너뛴다() {
        String text = ProductVectorText.of(product("Nike", null, null, null, null, null, null, null));

        assertThat(text).isEqualTo("Nike");
    }

    @Test
    void 알_수_없는_등급은_넣지_않는다() {
        String text = ProductVectorText.of(
                product("Nike", "Dunk", null, null, "UNKNOWN", null, null, null));

        assertThat(text).isEqualTo("Nike Dunk");
    }
}
