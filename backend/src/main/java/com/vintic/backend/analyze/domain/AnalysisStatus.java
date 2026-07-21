package com.vintic.backend.analyze.domain;

public enum AnalysisStatus {
    CREATED,
    IMAGE_UPLOADED,
    VISION_PROCESSING,
    AWAITING_USER_CONFIRMATION,
    PRICING_PROCESSING,
    COMPLETED,
    VISION_FAILED,
    PRICING_FAILED
}
