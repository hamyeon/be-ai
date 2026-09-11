package com.vintic.backend.analyze.job;

import com.vintic.backend.analyze.job.processor.AnalysisPayload;
import com.vintic.backend.common.dto.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// contracts.md §2: "POST /api/analyses/sync -> Day13 동기 대 비동기 비교용, 비동기 경로와 같은
// Real/Fake Processor 사용". decisions.md: "experiment profile에서만 활성. prod-api profile에서는
// 등록되지 않는다" - @Profile("experiment-api")로 이 컨트롤러 자체를 그 프로필에서만 빈으로
// 등록한다(AnalysisJobController는 손대지 않음 - 그 컨트롤러의 비동기 제출/조회는 계속 모든
// 프로필에서 그대로 열려 있다).
//
// 요청/응답 DTO는 계약에 없는 새 필드를 만들지 않기 위해 기존 타입을 그대로 재사용한다 -
// 요청은 async 제출과 동일한 SubmitAnalysisRequest(objectKey), 응답은 AnalysisProcessor의
// 결과 타입인 AnalysisPayload(rawResult) 그대로.
@RestController
@RequestMapping("/api/analyses")
@Profile("experiment-api")
public class SyncAnalysisController {

    private final SyncAnalysisService syncAnalysisService;

    public SyncAnalysisController(SyncAnalysisService syncAnalysisService) {
        this.syncAnalysisService = syncAnalysisService;
    }

    @PostMapping("/sync")
    public ResponseEntity<ApiResponse<AnalysisPayload>> analyzeSync(@Valid @RequestBody SubmitAnalysisRequest request) {
        AnalysisPayload result = syncAnalysisService.analyze(request.objectKey());
        return ResponseEntity.ok(ApiResponse.success(result));
    }
}
