package com.vintic.backend.purchasegoal;

import com.vintic.backend.common.dto.ApiResponse;
import com.vintic.backend.purchasegoal.dto.CreatePurchaseGoalRequest;
import com.vintic.backend.purchasegoal.dto.PurchaseGoalCancelResponse;
import com.vintic.backend.purchasegoal.dto.PurchaseGoalResponse;
import com.vintic.backend.purchasegoal.service.PurchaseGoalCommandService;
import com.vintic.backend.purchasegoal.service.PurchaseGoalQueryService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// GoalParseController(/api/purchase-goals/parse)가 만드는 초안을 사람이 확인·수정한 뒤
// 여기로 확정한다. 파싱 API 없이 직접 조건을 채워 등록하는 것도 이 엔드포인트 하나로 가능하다 -
// 이 컨트롤러는 GoalDraft/파싱 흐름에 의존하지 않는다.
@RestController
@RequestMapping("/api/purchase-goals")
public class PurchaseGoalController {

    private final PurchaseGoalCommandService purchaseGoalCommandService;
    private final PurchaseGoalQueryService purchaseGoalQueryService;

    public PurchaseGoalController(
            PurchaseGoalCommandService purchaseGoalCommandService,
            PurchaseGoalQueryService purchaseGoalQueryService
    ) {
        this.purchaseGoalCommandService = purchaseGoalCommandService;
        this.purchaseGoalQueryService = purchaseGoalQueryService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<PurchaseGoalResponse>> createGoal(
            @Valid @RequestBody CreatePurchaseGoalRequest request,
            @RequestAttribute("currentUserId") Long currentUserId
    ) {
        PurchaseGoalResponse response = purchaseGoalCommandService.createGoal(request, currentUserId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<PurchaseGoalResponse>>> getMyGoals(
            @RequestAttribute("currentUserId") Long currentUserId
    ) {
        List<PurchaseGoalResponse> response = purchaseGoalQueryService.getMyGoals(currentUserId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<PurchaseGoalResponse>> getGoal(
            @PathVariable Long id,
            @RequestAttribute("currentUserId") Long currentUserId
    ) {
        PurchaseGoalResponse response = purchaseGoalQueryService.getGoal(id, currentUserId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<PurchaseGoalCancelResponse>> cancelGoal(
            @PathVariable Long id,
            @RequestAttribute("currentUserId") Long currentUserId
    ) {
        PurchaseGoalCancelResponse response = purchaseGoalCommandService.cancelGoal(id, currentUserId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
