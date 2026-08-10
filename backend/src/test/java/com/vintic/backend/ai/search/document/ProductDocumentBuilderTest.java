package com.vintic.backend.ai.search.document;

import com.vintic.backend.product.domain.Product;
import com.vintic.backend.user.domain.User;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProductDocumentBuilderTest {

    private final ProductDocumentBuilder sut = new ProductDocumentBuilder();

    @Test
    void 상품_하나는_atomic_문서_하나로_변환된다() {
        User seller = User.register("seller@vintic.local", "seller", null);
        Product product = new Product(
                seller,
                List.of("https://example.com/a.jpg"),
                "Nike",
                "Dunk Low",
                "Panda",
                270,
                "B",
                "PARTIAL",
                300000,
                350000,
                "285,000원 ~ 315,000원",
                290000,
                "테스트 사유",
                "박스 없이 신발만 있어요. 밑창에 약간의 사용감이 있습니다."
        );

        SearchDocument document = sut.build(product);

        assertThat(document.sourceType()).isEqualTo("PRODUCT");
        assertThat(document.text()).contains("Nike", "Dunk Low", "Panda");
        assertThat(document.text()).contains("박스 없이 신발만 있어요");
        assertThat(document.text()).contains("사이즈 270");
        assertThat(document.text()).contains("상태 등급 B");
        assertThat(document.metadata().get("brand")).isEqualTo("Nike");
        assertThat(document.metadata().get("price")).isEqualTo(290000);
    }
}
