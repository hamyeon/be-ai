package com.vintic.backend.ai.purchase.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// POST /api/purchase-goals/parse 요청. 설계안 6-1의 { "text": "..." }.
//
// 길이 상한은 프롬프트 비용과 오남용 방지용이다. 구매 목표 한 문장이 300자를 넘을 일은 없다.
public record GoalParseRequest(
        @NotBlank(message = "구매 목표 문장은 필수입니다.")
        @Size(max = 300, message = "구매 목표 문장은 300자 이하여야 합니다.")
        String text
) {
}
