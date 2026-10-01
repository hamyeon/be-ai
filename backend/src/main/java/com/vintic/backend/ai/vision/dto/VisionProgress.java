package com.vintic.backend.ai.vision.dto;

// 3단계 Vision 분석 도중 한 단계가 끝났을 때의 잠정 결과(#106).
//
// 분석 전체는 10~20초가 걸리지만 1단계(브랜드·모델·색상)는 몇 초면 끝난다. 사용자가 빈 화면을 보지 않게
// 먼저 보여줄 값만 담는다. 값은 전부 근거 검증기(VisionEvidenceValidator)를 거친 것이다.
//
// 잠정값이다. 2단계 라벨 판독이 1단계 추정을 덮을 수 있고(라벨이 추정을 이긴다), 최종 결과에서 또 바뀔 수 있다.
public record VisionProgress(
        int completedStages,
        int totalStages,
        String brand,
        String modelName,
        String color,
        // 라벨을 읽는 2단계가 끝나야 채워진다
        Integer size
) {
}
