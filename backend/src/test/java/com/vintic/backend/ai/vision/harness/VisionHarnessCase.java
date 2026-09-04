package com.vintic.backend.ai.vision.harness;

import java.util.List;

// 하네스 평가 셋 한 건. src/test/resources/vision/harness-fixtures.json의 항목과 1:1로 대응한다.
//
// imageBaseUrls는 쿼리 파라미터가 없는 원본 이미지 URL이다. 크롤링 원본에는
// "?s=300x300&t=crop"이 붙어 있어 그대로 쓰면 300x300 썸네일이 내려오는데,
// 해상도 자체가 실험 변수라서 픽스처에는 원본만 담고 변형은 VisionHarnessImageVariant가 붙인다.
public record VisionHarnessCase(
        String id,
        List<String> imageBaseUrls,
        String sourceItemUrl,
        Expected expected,
        String groundTruthSource
) {

    // 판매글 본문에 적힌 값을 정답으로 라벨링한 것. 정답이 없는 필드는 null이고 채점에서 제외된다.
    public record Expected(
            List<String> brand,          // 허용 가능한 브랜드 표기. 하나라도 맞으면 정답 (예: 조던 -> "Nike" 또는 "Jordan")
            List<String> modelKeywords,  // modelName에 전부 포함돼야 정답으로 보는 키워드
            List<String> colorKeywords,  // 허용 가능한 색상 표기. 하나라도 포함되면 정답, 색 계열만 맞으면 근사(#90)
            Integer size,
            Boolean boxIncluded,
            String conditionGrade
    ) {
    }
}
