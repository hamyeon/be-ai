package com.vintic.backend.ai.vision.dto;

import java.util.List;

// analysisId는 호출 기록을 어느 분석 세션에 묶을지 알려주는 값이다.
// 분석 자체에는 쓰이지 않으므로, 세션 밖에서 부르는 경우(테스트·하네스)는 null로 둔다.
public record VisionAnalysisRequest(
        List<String> imageUrls,
        Long analysisId
) {

    public VisionAnalysisRequest(List<String> imageUrls) {
        this(imageUrls, null);
    }
}
