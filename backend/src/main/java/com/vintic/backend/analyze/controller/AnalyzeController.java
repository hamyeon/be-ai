package com.vintic.backend.analyze.controller;

import com.vintic.backend.analyze.dto.AnalysisStatusResponse;
import com.vintic.backend.analyze.dto.AnalyzeAcceptedResponse;
import com.vintic.backend.analyze.dto.AnalyzeCancelResponse;
import com.vintic.backend.analyze.service.ProductAnalyzeService;
import com.vintic.backend.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

// 프론트가 보낸 이미지를 받아 S3 업로드 및 Queue 적재까지 수행하고, 실제 Vision 분석은
// 비동기로 처리되므로 taskId(analysisId)로 상태를 폴링하는 API를 함께 제공하는 컨트롤러
@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class AnalyzeController {

    private final ProductAnalyzeService productAnalyzeService;

    @PostMapping(value = "/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<AnalyzeAcceptedResponse>> analyzeImage(
            @RequestPart("images") List<MultipartFile> images,
            @RequestAttribute("currentUserId") Long userId
    ) {
        // 에러나면 서비스가 알아서 던지고 Advice가 알아서 처리함
        AnalyzeAcceptedResponse response = productAnalyzeService.submitForAnalysis(images, userId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(response));
    }

    @GetMapping("/analyze/{taskId}")
    public ResponseEntity<ApiResponse<AnalysisStatusResponse>> getAnalysisStatus(
            @PathVariable Long taskId,
            @RequestAttribute("currentUserId") Long userId
    ) {
        AnalysisStatusResponse response = productAnalyzeService.getStatus(taskId, userId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // #127: 이미지 분석 화면의 X 버튼. 반복 호출해도 같은 성공 응답(status="CANCELLED")을 받는다.
    @PostMapping("/analyze/{taskId}/cancel")
    public ResponseEntity<ApiResponse<AnalyzeCancelResponse>> cancelAnalysis(
            @PathVariable Long taskId,
            @RequestAttribute("currentUserId") Long userId
    ) {
        AnalyzeCancelResponse response = productAnalyzeService.cancel(taskId, userId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
