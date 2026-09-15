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

    // #102: 프로덕션이 분석용 사본을 만들 때 쓰는 해상도(vision.image.max-edge)와 같은 급을 재기 위한 변형.
    // 프로덕션은 ImageResizer가 비율을 지키며 줄이지만, 여기서는 당근 CDN에 있는 크롭 리사이즈를
    // 빌려 쓴다(업로드 없이 해상도만 바꾸려면 이 방법뿐이다). 정사각이 아닌 원본은 잘리므로
    // "해상도를 낮추면 정확도가 어떻게 되나"의 근사치로만 읽는다.
    RESIZED_768("?q=82&s=768x768&t=crop&service=webapp&f=webp"),
    RESIZED_512("?q=82&s=512x512&t=crop&service=webapp&f=webp"),

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
