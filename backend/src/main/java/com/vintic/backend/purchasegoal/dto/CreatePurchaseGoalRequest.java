package com.vintic.backend.purchasegoal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.OffsetDateTime;

// POST /api/purchase-goals 요청. 파싱 API(GoalDraft) 없이 사용자가 직접 확정한 조건을 받는다.
// confidence/warnings는 초안(GoalDraft) 전용 필드라 여기 없다 - 사람이 직접 입력한 값이므로
// 신뢰도를 따로 매길 이유가 없다. userId는 여기 없다 - 인증(currentUserId)에서만 가져온다.
public record CreatePurchaseGoalRequest(

        // GoalDraft.brand와 동일하게 비워둘 수 있다 - modelQuery만으로도 목표를 특정할 수 있어
        // brand 누락만으로 요청 전체를 거절하지 않는다.
        String brand,

        // 시세 카탈로그 밖의 모델은 비워둘 수 있다(matching 단계에서 후보를 못 찾을 뿐 등록
        // 자체를 막을 이유는 아니다).
        String modelKey,

        @NotBlank(message = "모델 설명은 필수입니다.")
        String modelQuery,

        @NotBlank(message = "최소 상태 등급은 필수입니다.")
        String minCondition,

        // 값이 있을 때만 이후 hard filter 조건으로 쓰인다 - 없으면 사이즈로 거르지 않는다.
        @Positive(message = "사이즈는 0보다 커야 합니다.")
        Integer sizeKr,

        @NotNull(message = "예산 상한은 필수입니다.")
        @Positive(message = "예산 상한은 0보다 커야 합니다.")
        Long hardMaxAmount,

        String freeTextConditions,

        @NotNull(message = "마감 시각은 필수입니다.")
        OffsetDateTime deadline
) {
}
