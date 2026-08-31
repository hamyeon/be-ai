package com.vintic.backend.backupoffer;

import com.vintic.backend.backupoffer.dto.BackupOfferResponse;
import com.vintic.backend.backupoffer.service.BackupOfferQueryService;
import com.vintic.backend.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/backup-offers")
public class BackupOfferController {

    private final BackupOfferQueryService backupOfferQueryService;

    public BackupOfferController(BackupOfferQueryService backupOfferQueryService) {
        this.backupOfferQueryService = backupOfferQueryService;
    }

    @Operation(
            summary = "차순위 구매 제안 조회",
            description = "backupOfferId는 GET /auctions/{auctionId}/result의 backupOfferId로 획득한다. "
                    + "accept/decline은 아직 구현되지 않았다(#56-3)."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 차순위 제안(40403)")
    })
    // 응답 자체는 요청자별로 달라지지 않지만(§15엔 소유자 검증이 없다), 계약상 인증이 필수이므로
    // AuctionController.getAutoBidRecommendation과 동일하게 currentUserId 파라미터로 검증만 건다.
    @GetMapping("/{backupOfferId}")
    public ResponseEntity<ApiResponse<BackupOfferResponse>> getBackupOffer(
            @PathVariable Long backupOfferId,
            @RequestAttribute("currentUserId") Long userId
    ) {
        BackupOfferResponse response = backupOfferQueryService.getBackupOffer(backupOfferId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
