package com.vintic.backend.auction.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record ChangeStartPriceRequest(
        @NotNull(message = "시작가는 필수입니다.")
        @Positive(message = "시작가는 0보다 커야 합니다.")
        Long startPrice
) {
}
