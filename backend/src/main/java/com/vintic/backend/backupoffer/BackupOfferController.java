package com.vintic.backend.backupoffer;

import com.vintic.backend.backupoffer.dto.BackupOfferAcceptResponse;
import com.vintic.backend.backupoffer.dto.BackupOfferDeclineResponse;
import com.vintic.backend.backupoffer.dto.BackupOfferResponse;
import com.vintic.backend.backupoffer.service.BackupOfferQueryService;
import com.vintic.backend.backupoffer.service.BackupOfferService;
import com.vintic.backend.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/backup-offers")
public class BackupOfferController {

    private final BackupOfferQueryService backupOfferQueryService;
    private final BackupOfferService backupOfferService;

    public BackupOfferController(BackupOfferQueryService backupOfferQueryService, BackupOfferService backupOfferService) {
        this.backupOfferQueryService = backupOfferQueryService;
        this.backupOfferService = backupOfferService;
    }

    @Operation(
            summary = "차순위 구매 제안 조회",
            description = "backupOfferId는 GET /auctions/{auctionId}/result의 backupOfferId로 획득한다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증이 필요합니다(40101)"),
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

    // #56-3: 소유자(=candidate) 검증을 하지 않는다 - 계약이 별도 403을 정의하지 않는다
    // (BackupOfferCommandService 클래스 주석 참고, 알려진 gap).
    @Operation(
            summary = "차순위 구매 수락",
            description = "차순위 구매를 수락하고 주문을 생성한다. paymentDeadline은 수락 시각 + 24시간이다(원 경매의 "
                    + "endsAt, 제안의 deadline과 무관). 동일 Idempotency-Key로 재시도해도 새로 처리되지 않고 최초 성공 "
                    + "응답이 그대로 반환된다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "수락 성공 또는 동일 요청 replay"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증이 필요합니다(40101)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 차순위 제안(40403)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "차순위 구매 기한 만료(40911) / 이미 처리된 제안(40912) / Idempotency payload mismatch(40905)")
    })
    @PostMapping("/{backupOfferId}/accept")
    public ResponseEntity<ApiResponse<BackupOfferAcceptResponse>> accept(
            @PathVariable Long backupOfferId,
            @RequestAttribute("currentUserId") Long userId,
            @Parameter(description = "요청 재시도 식별용 키. 동일 (user, backupOfferId, key)는 같은 요청으로 취급된다.", required = true)
            @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        BackupOfferAcceptResponse response = backupOfferService.accept(backupOfferId, userId, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    @Operation(
            summary = "차순위 구매 거절",
            description = "차순위 구매를 거절한다. 다음 순위(rank 3까지만) 후보가 있으면 서버가 새 BackupOffer를 생성한다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "거절 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증이 필요합니다(40101)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 차순위 제안(40403)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "이미 처리된 제안(40912)")
    })
    @PostMapping("/{backupOfferId}/decline")
    public ResponseEntity<ApiResponse<BackupOfferDeclineResponse>> decline(
            @PathVariable Long backupOfferId,
            @RequestAttribute("currentUserId") Long userId
    ) {
        BackupOfferDeclineResponse response = backupOfferService.decline(backupOfferId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
