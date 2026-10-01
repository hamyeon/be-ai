package com.vintic.backend.analyze.queue;

import java.util.List;

// Redis Stream으로 전달하는 분석 작업 메시지. MultipartFile은 요청 종료 후 사용할 수 없으므로
// S3 업로드가 끝난 뒤의 analysisId와 URL만 담는다.
public record AnalysisTaskMessage(
        Long analysisId,
        List<String> imageUrls,
        // #102: Vision에 넘길 축소 사본 URL. 원본(imageUrls)은 사용자에게 보이는 상품 사진이라
        // 화질을 낮출 수 없어, 분석용으로만 따로 만든 사본을 여기에 담는다.
        List<String> analysisImageUrls
) {

    // Vision 호출에 쓸 URL. 사본이 없으면 원본으로 되돌아간다 - 리사이즈 도입 전에 큐에 들어간
    // 메시지(이 필드가 null)와 리사이즈가 필요 없거나 실패한 이미지를 같은 규칙으로 처리한다.
    public List<String> visionImageUrls() {
        return analysisImageUrls == null || analysisImageUrls.isEmpty() ? imageUrls : analysisImageUrls;
    }
}
