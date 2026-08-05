package com.vintic.backend.ai.vision.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

// Vision 분석 결과. 3단계(실루엣 -> 라벨 -> 컨디션) 결과를 하나로 합친 형태다.
//
// evidence는 각 필드의 근거이고, 근거가 없는 값은 저장 전에 제거된다(VisionEvidenceValidator).
// 이미 저장돼 있는 예전 형식의 JSON을 다시 읽어야 해서 모르는 필드는 무시한다.
@JsonIgnoreProperties(ignoreUnknown = true)
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
        List<VisionAnalysisCandidate> candidates,
        List<VisionDefect> defects,
        List<VisionEvidence> evidence
) {
}
