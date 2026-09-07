package com.vintic.backend.analyze.job;

import com.vintic.backend.common.dto.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/analyses")
public class AnalysisJobController {

    private final ProductAnalysisJobService jobService;
    private final AnalysisJobQueryService jobQueryService;

    public AnalysisJobController(ProductAnalysisJobService jobService, AnalysisJobQueryService jobQueryService) {
        this.jobService = jobService;
        this.jobQueryService = jobQueryService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<AnalysisJobResponse>> submit(
            @RequestAttribute("currentUserId") Long userId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody SubmitAnalysisRequest request
    ) {
        ProductAnalysisJob job = jobService.submit(userId, request.objectKey(), idempotencyKey);
        return ResponseEntity.status(httpStatusFor(job.getStatus()))
                .body(ApiResponse.success(AnalysisJobResponse.from(job)));
    }

    @GetMapping("/{analysisId}")
    public ResponseEntity<ApiResponse<AnalysisJobResponse>> getStatus(
            @PathVariable Long analysisId,
            @RequestAttribute("currentUserId") Long userId
    ) {
        ProductAnalysisJob job = jobQueryService.getOwnedJob(analysisId, userId);
        return ResponseEntity.ok(ApiResponse.success(AnalysisJobResponse.from(job)));
    }

    // FAILED는 이번 단계에서 도달할 경로가 없다(AnalysisProcessor/Worker가 아직 없어 markFailed
    // 자체가 없음). PUBLISH_FAILED(발행조차 안 됨)와 달리 FAILED는 큐에 들어간 뒤 처리가 끝난
    // 상태라 503(재시도 유도)이 아니라 200으로 둔다 - 임시 방어값이며, 실패 상세 DTO와
    // 영구/재시도 소진 구분은 Worker 오류 계약을 구현하는 Day3~4에서 확정한다.
    private HttpStatus httpStatusFor(AnalysisJobStatus status) {
        return switch (status) {
            case PENDING, QUEUED, PROCESSING -> HttpStatus.ACCEPTED;
            case COMPLETED, FAILED -> HttpStatus.OK;
            case PUBLISH_FAILED -> HttpStatus.SERVICE_UNAVAILABLE;
        };
    }
}
