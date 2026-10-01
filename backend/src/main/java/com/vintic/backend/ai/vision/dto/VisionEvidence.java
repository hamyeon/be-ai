package com.vintic.backend.ai.vision.dto;

// Vision이 어떤 필드를 왜 그렇게 판단했는지에 대한 근거 한 건.
//
// 이 값이 없는 필드는 근거 없는 주장이므로 파이프라인에서 버린다(VisionEvidenceValidator).
// "근거를 대라"는 지시는 프롬프트만으로는 지켜질 수도 안 지켜질 수도 있어서, 코드에서 강제한다.
public record VisionEvidence(
        String field,          // 근거가 붙는 결과 필드명 (brand, size, conditionGrade ...)
        Integer imageIndex,    // 몇 번째 이미지에서 봤는지 (0부터)
        String region,         // 신발/포장의 어느 부위인지 (tongue_label, outsole, box_label ...)
        String observedText,   // 이미지에서 실제로 읽어낸 글자. 글자 근거가 아니면 null
        String observation,    // 무엇이 보이는지에 대한 한 문장 (한국어)
        String sourceChunkId   // 참조 데이터를 인용한 경우의 청크 ID. 검색 결과를 프롬프트에 넣기 전까지는 항상 null
) {
}
