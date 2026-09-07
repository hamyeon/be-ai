package com.vintic.backend.analyze.job;

import jakarta.validation.constraints.NotBlank;

public record SubmitAnalysisRequest(@NotBlank String objectKey) {
}
