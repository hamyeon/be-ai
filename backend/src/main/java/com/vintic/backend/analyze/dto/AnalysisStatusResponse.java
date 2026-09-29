package com.vintic.backend.analyze.dto;

import com.vintic.backend.ai.vision.dto.VisionAnalysisCandidate;
import com.vintic.backend.ai.vision.dto.VisionDefect;

import java.util.List;

// GET /api/products/analyze/{taskId} 응답. 상태에 따라 Vision 필드나 실패 필드가 비어있을 수 있다.
//
// AWAITING_USER_CONFIRMATION 상태에서 사용자가 값을 확인/수정하는 화면을 그릴 수 있는 만큼만 담는다.
// Vision이 확신하지 못한 항목을 사용자에게 물어보려면 결과값뿐 아니라 warnings/needsUserConfirmation이
// 같이 내려가야 한다 - 근거가 없어 비워진 필드는 그냥 null로만 보이기 때문이다.
//
// evidence(판단 근거)는 일부러 뺐다. 항목마다 한국어 문장이 붙어 응답이 커지는데 폴링으로 반복
// 호출되는 API이고, 사용자에게 보여줄 정보도 아니다. 필요하면 vision_result_json에 그대로 남아 있다.
public record AnalysisStatusResponse(
        Long analysisId,
        String status,
        List<String> imageUrls,

        // #106: VISION_PROCESSING 동안 끝난 단계 수와 그때까지의 잠정 결과. 그 밖의 상태이거나
        // 아직 1단계도 안 끝났으면 둘 다 null이다. 필드만 추가했으므로 쓰지 않는 클라이언트는 영향이 없다.
        VisionProgress visionProgress,
        PreliminaryVision preliminary,

        // 사용자가 확인/수정할 상품 정보
        String brand,
        String modelName,
        String color,
        Integer size,
        Boolean boxIncluded,
        String conditionDescription,
        String conditionGrade,
        List<VisionDefect> defects,

        // 어디까지 믿을 수 있는지, 무엇을 사용자에게 물어봐야 하는지
        List<VisionAnalysisCandidate> candidates,
        Double confidence,
        Boolean needsUserConfirmation,
        List<String> warnings,

        String failureStage,
        String failureMessage
) {

    public record VisionProgress(int completedStages, int totalStages) {
    }

    // 잠정값이다. 2단계 라벨 판독이 1단계 추정을 덮을 수 있고, 최종 결과(brand 등 위 필드)에서 또 바뀔 수 있다.
    // size는 라벨을 읽는 2단계가 끝나야 채워진다.
    public record PreliminaryVision(String brand, String modelName, String color, Integer size) {
    }
}
