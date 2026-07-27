package com.vintic.backend.curation.dto;

// 큐레이션 한 묶음에 들어가는 상품 요약 정보. 실제 추천으로 교체될 때도 이 형태를 그대로 유지한다.
public record CurationItem(
        Long productId,
        String brand,
        String modelName,
        String title,
        Integer price,
        String imageUrl
) {
}
