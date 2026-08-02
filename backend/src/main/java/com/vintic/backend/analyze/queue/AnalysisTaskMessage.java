package com.vintic.backend.analyze.queue;

import java.util.List;

// Redis Stream으로 전달하는 분석 작업 메시지. MultipartFile은 요청 종료 후 사용할 수 없으므로
// S3 업로드가 끝난 뒤의 analysisId와 imageUrls만 담는다.
public record AnalysisTaskMessage(
        Long analysisId,
        List<String> imageUrls
) {
}
