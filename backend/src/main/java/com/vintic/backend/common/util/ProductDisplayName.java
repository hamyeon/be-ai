package com.vintic.backend.common.util;

import com.vintic.backend.product.domain.Product;

// #55 gap: Product 엔티티에는 FINAL contract §1의 product.name/subName에 대응하는 전용 컬럼이
// 없다 - brand/model/colorway만 구조화 필드로 존재하고(등록 폼: CreateProductRequest), 별도
// "상품명"/"영문 상품명" 개념이 도메인에 없다. 여기서는 fake 데이터를 만들지 않고 실제 저장된
// 구조화 필드만 조합한다 - subName은 브랜드를 뺀 model만 재사용한다(모델명이 라틴 문자로
// 저장되는 경우가 많다는 관찰에 근거하되, 실제 영문명 컬럼은 아니다). 전용 name/subName 컬럼
// 도입 여부는 #55 완료 보고의 gap 항목으로 남긴다 - 이 클래스가 그 gap의 임시 근사치다.
public final class ProductDisplayName {

    private ProductDisplayName() {
    }

    public static String name(Product product) {
        StringBuilder sb = new StringBuilder();
        appendIfPresent(sb, product.getBrand());
        appendIfPresent(sb, product.getModel());
        appendIfPresent(sb, product.getColorway());
        return sb.toString();
    }

    public static String subName(Product product) {
        return product.getModel();
    }

    private static void appendIfPresent(StringBuilder sb, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(' ');
        }
        sb.append(value.trim());
    }
}
