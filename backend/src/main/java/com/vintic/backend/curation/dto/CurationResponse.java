package com.vintic.backend.curation.dto;

import java.util.List;

// 구매자에게 제공하는 추천 상품 묶음/테마별 컬렉션 하나.
// curationId는 "최근 인기 상품", "20만원 이하 추천 상품"처럼 테마를 식별하는 고정 키.
public record CurationResponse(
        String curationId,
        String title,
        String description,
        List<CurationItem> items
) {
}
