package com.vintic.backend.ai.dto;

public record VisionAnalysisCandidate(
        String brand,
        String modelName,
        String color,
        Double confidence
) {
}
