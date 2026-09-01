package com.vintic.backend.penalty;

import com.vintic.backend.common.dto.ApiResponse;
import com.vintic.backend.penalty.dto.MyPenaltyResponse;
import com.vintic.backend.penalty.service.PenaltyQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me")
public class PenaltyController {

    private final PenaltyQueryService penaltyQueryService;

    public PenaltyController(PenaltyQueryService penaltyQueryService) {
        this.penaltyQueryService = penaltyQueryService;
    }

    @Operation(
            summary = "내 페널티 조회",
            description = "noShowCount, bidRestricted, bidRestrictedUntil의 single source of truth. "
                    + "noShowCount는 PAYMENT_EXPIRED penalty만 센다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증이 필요합니다(40101)")
    })
    @GetMapping("/penalties")
    public ResponseEntity<ApiResponse<MyPenaltyResponse>> getMyPenalties(
            @RequestAttribute("currentUserId") Long userId
    ) {
        MyPenaltyResponse response = penaltyQueryService.getMyPenalties(userId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
