package com.vintic.backend.analyze.job.upload;

import com.vintic.backend.common.dto.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/uploads")
public class UploadController {

    private final PresignedUploadService presignedUploadService;

    public UploadController(PresignedUploadService presignedUploadService) {
        this.presignedUploadService = presignedUploadService;
    }

    @PostMapping("/presigned-url")
    public ResponseEntity<ApiResponse<PresignedUploadResponse>> createPresignedUrl() {
        PresignedUploadResponse response = presignedUploadService.createPresignedUpload();
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
