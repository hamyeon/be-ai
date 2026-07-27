package com.vintic.backend.ai.search.document;

import com.vintic.backend.product.domain.Product;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// 등록된 Product를 검색/임베딩 대상 SearchDocument로 변환한다.
// 검색 대상 텍스트: 상품 제목(브랜드+모델+컬러웨이), 상품 설명, 사이즈, 상태 등급, 구성품 상태.
@Component
public class ProductDocumentBuilder {

    public SearchDocument build(Product product) {
        String title = Stream.of(product.getBrand(), product.getModel(), product.getColorway())
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.joining(" "));

        String text = Stream.of(
                        title,
                        product.getDescription(),
                        product.getSizeKr() != null ? "사이즈 " + product.getSizeKr() : null,
                        product.getConditionGrade() != null ? "상태 등급 " + product.getConditionGrade() : null,
                        product.getComponentStatus() != null ? "구성품 상태 " + product.getComponentStatus() : null
                )
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.joining("\n"));

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("brand", product.getBrand());
        metadata.put("modelName", product.getModel());
        metadata.put("colorway", product.getColorway());
        metadata.put("sizeKr", product.getSizeKr());
        metadata.put("conditionGrade", product.getConditionGrade());
        metadata.put("price", product.getFinalPrice());

        return new SearchDocument(
                "product-" + product.getId(),
                "PRODUCT",
                product.getId(),
                text,
                metadata
        );
    }
}
