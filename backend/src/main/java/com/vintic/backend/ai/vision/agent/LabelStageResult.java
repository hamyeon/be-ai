package com.vintic.backend.ai.vision.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.vintic.backend.ai.vision.dto.VisionEvidence;
import com.vintic.backend.ai.vision.dto.VisionUnreadable;

import java.util.List;

// 2단계(라벨/로고) 응답. prompts/vision/label-v2.schema.json과 필드가 1:1로 대응한다.
//
// brand/modelName은 라벨로 확인되거나 정정된 경우에만 채워진다. 라벨이 말해주지 않으면 null이고,
// 그때는 1단계 추정값을 그대로 쓴다.
@JsonIgnoreProperties(ignoreUnknown = true)
public record LabelStageResult(
        Integer size,
        String sizeLabelText,
        String modelCode,
        String brand,
        String modelName,
        Boolean boxIncluded,
        List<VisionEvidence> evidence,
        List<VisionUnreadable> unreadable
) {
}
