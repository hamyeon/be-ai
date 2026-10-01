package com.vintic.backend.ai.vision.service;

import com.vintic.backend.ai.vision.dto.VisionAnalysisRequest;
import com.vintic.backend.ai.vision.dto.VisionAnalysisResult;

public interface VisionAnalysisService {

    VisionAnalysisResult analyze(VisionAnalysisRequest request);

    // 단계가 끝날 때마다 잠정 결과를 받고 싶을 때(#106). 단계가 없는 구현(한 번에 다 묻는 V1)은
    // 진행을 알리지 않고 결과만 돌려준다.
    default VisionAnalysisResult analyze(VisionAnalysisRequest request, VisionProgressListener progressListener) {
        return analyze(request);
    }
}
