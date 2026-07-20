package com.vintic.backend.ai.service;

import com.vintic.backend.ai.dto.VisionAnalysisRequest;
import com.vintic.backend.ai.dto.VisionAnalysisResult;

public interface VisionAnalysisService {

    VisionAnalysisResult analyze(VisionAnalysisRequest request);
}
