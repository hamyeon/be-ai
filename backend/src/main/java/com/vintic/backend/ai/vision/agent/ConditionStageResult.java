package com.vintic.backend.ai.vision.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.vintic.backend.ai.vision.dto.ConditionGrade;
import com.vintic.backend.ai.vision.dto.VisionDefect;
import com.vintic.backend.ai.vision.dto.VisionEvidence;
import com.vintic.backend.ai.vision.dto.VisionUnreadable;

import java.util.List;

// 3단계(오염/마모) 응답. prompts/vision/condition-v2.schema.json과 필드가 1:1로 대응한다.
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConditionStageResult(
        ConditionGrade conditionGrade,
        String conditionDescription,
        List<VisionDefect> defects,
        Double confidence,
        Boolean needsUserConfirmation,
        List<VisionEvidence> evidence,
        List<VisionUnreadable> unreadable
) {
}
