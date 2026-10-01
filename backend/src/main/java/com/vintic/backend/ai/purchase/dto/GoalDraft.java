package com.vintic.backend.ai.purchase.dto;

import java.util.List;

// 자연어 구매 목표를 구조화한 초안. 설계안 6-1의 응답이다.
//
// 초안이다 - 사용자가 확인 화면에서 고친 뒤 POST /purchase-goals로 확정한다. 여기 값이
// 사람 확인 없이 cap이나 입찰 금액에 닿는 경로는 없다.
//
// 계약(6-1) 필드: modelQuery / minCondition / hardMaxAmount / freeTextConditions / confidence.
// 아래는 계약에 없지만 추가한 필드다(모두 nullable, 기존 필드 의미는 바꾸지 않았다):
//   - brand, modelKey: pre-filter와 시세 조회가 문자열 비교 대신 이 키로 매칭하기 위해.
//     modelKey가 null이면 시세 카탈로그 밖의 모델이라 v1 Agent는 후보를 찾지 못한다.
//   - sizeKr: 신발은 사이즈 없이 살 수 없는데 계약에 사이즈 칸이 없었다. 자유 조건에 섞어
//     soft로 두면 다른 사이즈를 사게 되므로 구조화 필드로 뺐다. 백엔드 pre-filter가 hard로
//     써야 한다(팀 합의 필요).
//   - warnings: 확인 화면에 띄울 안내. "사이즈 없음", "'박스 필수'는 v1에서 참고 사항" 등.
public record GoalDraft(
        String modelQuery,
        String brand,
        String modelKey,
        GoalCondition minCondition,
        Long hardMaxAmount,
        Integer sizeKr,
        String freeTextConditions,
        double confidence,
        List<String> warnings
) {

    public GoalDraft {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        confidence = Math.max(0.0, Math.min(1.0, confidence));
    }

    public static GoalDraft empty(List<String> warnings) {
        return new GoalDraft(null, null, null, null, null, null, null, 0.0, warnings);
    }
}
