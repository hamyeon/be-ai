package com.vintic.backend.ai.vision.dto;

public record VisionAnalysisCandidate(
        String brand,
        String modelName,
        String color,
        Double confidence
) {
}
