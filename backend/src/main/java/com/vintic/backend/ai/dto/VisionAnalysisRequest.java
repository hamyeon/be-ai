package com.vintic.backend.ai.dto;

import java.util.List;

public record VisionAnalysisRequest(
        List<String> imageUrls
) {
}
