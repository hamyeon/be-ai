package com.vintic.backend.analyze.domain;

public enum AnalysisStatus {
    CREATED,
    IMAGE_UPLOADED,
    QUEUED,
    VISION_PROCESSING,
    AWAITING_USER_CONFIRMATION,
    PRICING_PROCESSING,
    COMPLETED,
    IMAGE_UPLOAD_FAILED,
    QUEUE_FAILED,
    VISION_FAILED,
    PRICING_FAILED
}
