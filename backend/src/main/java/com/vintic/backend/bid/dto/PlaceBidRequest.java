package com.vintic.backend.bid.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record PlaceBidRequest(
        @NotNull @Positive Long amount
) {
}
