package com.vintic.backend.recommendation.service;

import com.vintic.backend.product.domain.Product;

import java.util.ArrayList;
import java.util.List;

// 상품을 임베딩할 텍스트로 바꾼다.
//
// 구조화 필드(브랜드/모델/컬러웨이/사이즈/상태/구성품)만 쓰고 판매글 설명은 넣지 않는다.
// 취향은 "어떤 브랜드·모델·가격대를 좋아하는가"에서 나오는데, 판매글에는 "네고 사절",
// "직거래만" 같은 취향과 무관한 문구가 많아 벡터를 흐린다.
//
// 가격은 구간으로 넣는다. 179,000과 181,000은 취향 관점에서 같은 가격대인데,
// 숫자를 그대로 넣으면 임베딩이 둘을 다른 토큰으로 본다.
public final class ProductVectorText {

    private ProductVectorText() {
    }

    public static String of(Product product) {
        List<String> parts = new ArrayList<>();
        addIfPresent(parts, product.getBrand());
        addIfPresent(parts, product.getModel());
        addIfPresent(parts, product.getColorway());

        if (product.getSizeKr() != null) {
            parts.add(product.getSizeKr() + "mm");
        }
        addIfPresent(parts, conditionText(product.getConditionGrade()));
        addIfPresent(parts, componentText(product.getComponentStatus()));
        addIfPresent(parts, priceBandText(product.getRecommendedPrice()));

        return String.join(" ", parts);
    }

    private static void addIfPresent(List<String> parts, String value) {
        if (value != null && !value.isBlank()) {
            parts.add(value.trim());
        }
    }

    // 등급 문자(A/B)만 넣으면 임베딩이 의미를 모른다. 사람이 읽는 표현으로 바꿔 넣는다.
    private static String conditionText(String conditionGrade) {
        if (conditionGrade == null || conditionGrade.isBlank()) {
            return null;
        }
        return switch (conditionGrade.trim().toUpperCase()) {
            case "DS" -> "미착용 새상품";
            case "S" -> "거의 새것";
            case "A" -> "상태 좋은 중고";
            case "B" -> "사용감 있는 중고";
            case "C" -> "사용감 많은 중고";
            default -> null;
        };
    }

    private static String componentText(String componentStatus) {
        if (componentStatus == null || componentStatus.isBlank()) {
            return null;
        }
        return switch (componentStatus.trim().toUpperCase()) {
            case "FULL" -> "구성품 모두 포함";
            case "PARTIAL" -> "구성품 일부 포함";
            case "NONE" -> "구성품 없음";
            default -> null;
        };
    }

    // 10만원 단위 구간. 취향은 "20만원대 신발을 본다" 수준으로 잡히면 충분하다.
    private static String priceBandText(Integer price) {
        if (price == null || price <= 0) {
            return null;
        }
        int band = price / 100_000;
        if (band == 0) {
            return "10만원 미만";
        }
        return "%d0만원대".formatted(band);
    }
}
