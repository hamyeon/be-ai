package com.vintic.backend.ai.search.document;

import java.util.Map;

// 검색/임베딩 대상이 되는 원본 단위. 상품 하나 = 문서 하나.
// metadata에는 가격/사이즈/브랜드처럼 벡터 검색이 아니라 MySQL 필터로 걸러야 하는 값들을 담아둔다.
public record SearchDocument(
        String documentId,
        String sourceType,
        Long sourceId,
        String text,
        Map<String, Object> metadata
) {
}
