package com.vintic.backend.ai.vision.dto;

import java.util.List;

public record VisionAnalysisRequest(
        List<String> imageUrls
) {
}
