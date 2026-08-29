package com.vintic.backend.autobid.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

// POST(등록)와 PATCH(수정) 요청 바디는 계약상 { "maxAmount": Long } 하나로 동일한 shape이라
// 커맨드별로 별도 record를 만들지 않고 공용으로 쓴다.
public record AutoBidMaxAmountRequest(
        @NotNull @Positive Long maxAmount
) {
}
