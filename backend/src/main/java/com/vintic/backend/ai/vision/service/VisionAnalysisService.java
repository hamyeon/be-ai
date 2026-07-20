package com.vintic.backend.ai.vision.service;

import com.vintic.backend.ai.vision.dto.VisionAnalysisRequest;
import com.vintic.backend.ai.vision.dto.VisionAnalysisResult;

public interface VisionAnalysisService {

    VisionAnalysisResult analyze(VisionAnalysisRequest request);
}
