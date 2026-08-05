package com.vintic.backend.ai.vision.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.vintic.backend.ai.vision.dto.VisionAnalysisCandidate;
import com.vintic.backend.ai.vision.dto.VisionEvidence;
import com.vintic.backend.ai.vision.dto.VisionUnreadable;

import java.util.List;

// 1단계(전체 형태) 응답. prompts/vision/silhouette-v2.schema.json과 필드가 1:1로 대응한다.
@JsonIgnoreProperties(ignoreUnknown = true)
public record SilhouetteStageResult(
        String silhouette,
        String brand,
        String modelName,
        String color,
        List<VisionAnalysisCandidate> candidates,
        List<VisionEvidence> evidence,
        List<VisionUnreadable> unreadable
) {
}
