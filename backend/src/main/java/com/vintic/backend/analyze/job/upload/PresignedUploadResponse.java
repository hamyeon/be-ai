package com.vintic.backend.analyze.job.upload;

import java.time.LocalDateTime;

public record PresignedUploadResponse(String objectKey, String uploadUrl, LocalDateTime expiresAt) {
}
