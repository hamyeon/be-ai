package com.vintic.backend.ai.vision.harness;

import java.util.List;

// 같은 매물 이미지를 어떤 해상도로 모델에 넘길지 고르는 변형.
//
// 크롤러가 수집해 둔 URL은 300x300 크롭 썸네일(약 17KB)이고, 쿼리를 떼면 원본(약 280KB)이 내려온다.
// OpenAI Vision의 detail 옵션을 high로 올려도 300x300 이미지에는 얻을 게 없으므로,
// 해상도와 detail은 같이 놓고 비교해야 의미가 있다.
public enum VisionHarnessImageVariant {

    // 크롤러 출력에 저장돼 있는 형태 그대로 (300x300 크롭)
    THUMBNAIL_300("?q=82&s=300x300&t=crop&service=webapp&f=webp"),

    // 쿼리 없이 원본 해상도
    ORIGIN("");

    private final String querySuffix;

    VisionHarnessImageVariant(String querySuffix) {
        this.querySuffix = querySuffix;
    }

    public List<String> apply(List<String> baseUrls) {
        return baseUrls.stream().map(baseUrl -> baseUrl + querySuffix).toList();
    }
}
