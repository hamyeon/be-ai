package com.vintic.backend.ai.vision.dto;

// 사진에서 실제로 보이는 하자 한 건. 컨디션 등급의 근거가 되는 항목이다.
public record VisionDefect(
        String type,        // crease, stain, sole_wear, scuff ...
        String location,    // toe_box, outsole, upper ...
        String severity,    // minor, moderate, severe
        String description  // 한국어 한 문장
) {
}
