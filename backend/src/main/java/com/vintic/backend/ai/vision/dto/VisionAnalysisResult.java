package com.vintic.backend.ai.vision.dto;

import java.util.List;

// ProductAnalysisPrompt.SYSTEM_PROMPT가 지시하는 JSON 스키마와 필드명을 그대로 맞춘 Vision 분석 결과
public record VisionAnalysisResult(
        String brand,
        String modelName,
        String color,
        Integer size,
        String conditionDescription,
        ConditionGrade conditionGrade,
        Boolean boxIncluded,
        Double confidence,
        Boolean needsUserConfirmation,
        List<String> warnings,
        List<VisionAnalysisCandidate> candidates
) {
}
