package com.vintic.backend.auction;

import com.vintic.backend.auction.dto.AuctionDetailResponse;
import com.vintic.backend.auction.dto.AuctionForfeitResponse;
import com.vintic.backend.auction.dto.AuctionLiveResponse;
import com.vintic.backend.auction.dto.AuctionResultResponse;
import com.vintic.backend.auction.dto.SimilarAuctionsResponse;
import com.vintic.backend.auction.service.AuctionQueryService;
import com.vintic.backend.auction.service.AuctionResultQueryService;
import com.vintic.backend.autobid.dto.AutoBidCancelResponse;
import com.vintic.backend.autobid.dto.AutoBidMaxAmountRequest;
import com.vintic.backend.autobid.dto.AutoBidMeResponse;
import com.vintic.backend.autobid.dto.AutoBidRecommendationResponse;
import com.vintic.backend.autobid.dto.AutoBidRegisterResponse;
import com.vintic.backend.autobid.dto.AutoBidUpdateResponse;
import com.vintic.backend.autobid.service.AutoBidQueryService;
import com.vintic.backend.autobid.service.AutoBidService;
import com.vintic.backend.bid.dto.BidHistoryResponse;
import com.vintic.backend.bid.dto.PlaceBidRequest;
import com.vintic.backend.bid.dto.PlaceBidResponse;
import com.vintic.backend.bid.service.BidQueryService;
import com.vintic.backend.bid.service.ManualBidService;
import com.vintic.backend.common.dto.ApiResponse;
import com.vintic.backend.like.dto.LikeResponse;
import com.vintic.backend.like.service.AuctionLikeService;
import com.vintic.backend.order.service.AuctionForfeitService;
import com.vintic.backend.recommendation.service.ActivityLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auctions")
public class AuctionController {

    private final AuctionQueryService auctionQueryService;
    private final AuctionResultQueryService auctionResultQueryService;
    private final BidQueryService bidQueryService;
    private final ManualBidService manualBidService;
    private final ActivityLogService activityLogService;
    private final AutoBidService autoBidService;
    private final AutoBidQueryService autoBidQueryService;
    private final AuctionLikeService auctionLikeService;
    private final AuctionForfeitService auctionForfeitService;

    public AuctionController(
            AuctionQueryService auctionQueryService,
            AuctionResultQueryService auctionResultQueryService,
            BidQueryService bidQueryService,
            ManualBidService manualBidService,
            ActivityLogService activityLogService,
            AutoBidService autoBidService,
            AutoBidQueryService autoBidQueryService,
            AuctionLikeService auctionLikeService,
            AuctionForfeitService auctionForfeitService
    ) {
        this.auctionQueryService = auctionQueryService;
        this.auctionResultQueryService = auctionResultQueryService;
        this.bidQueryService = bidQueryService;
        this.manualBidService = manualBidService;
        this.activityLogService = activityLogService;
        this.autoBidService = autoBidService;
        this.autoBidQueryService = autoBidQueryService;
        this.auctionLikeService = auctionLikeService;
        this.auctionForfeitService = auctionForfeitService;
    }

    // #55: 상세조회는 기존부터 비로그인 접근을 허용해왔다(가입 전 상품을 볼 수 있어야 한다는
    // 기존 결정) - 계약상 Authorization은 필수지만 이 endpoint를 인증 필수로 바꾸는 것은 도메인
    // 정책 변경이라 이번 범위에서 임의로 바꾸지 않았다(완료 보고 gap 참고). 헤더가 있으면
    // myState/isLiked를 개인화하고 추천용 행동 로그도 남긴다.
    // (인터셉터는 currentUserId를 파라미터로 받는 핸들러만 검증하므로 여기선 헤더를 직접 읽는다)
    @GetMapping("/{auctionId}")
    public ResponseEntity<ApiResponse<AuctionDetailResponse>> getAuction(
            @PathVariable Long auctionId,
            @RequestHeader(value = "X-User-Id", required = false) Long userId
    ) {
        AuctionDetailResponse response = auctionQueryService.getAuctionDetail(auctionId, userId);
        activityLogService.recordView(userId, auctionId, response.product().productId());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // Result는 별도 persisted entity가 아니라 Auction/Order/BackupOffer/Penalty 상태로부터 매
    // 조회마다 계산한다(side-effect free) - 낙찰자 Order는 이 endpoint가 만들지 않고
    // AuctionSettlementService가, BackupOffer/Penalty는 AuctionForfeitService가 별도 시점에
    // 만든다(AuctionResultQueryService 클래스 주석 참고).
    @Operation(
            summary = "경매 결과 조회",
            description = "낙찰/패찰/차순위 결과를 계산해 반환한다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 경매(40401)")
    })
    @GetMapping("/{auctionId}/result")
    public ResponseEntity<ApiResponse<AuctionResultResponse>> getResult(
            @PathVariable Long auctionId,
            @RequestAttribute("currentUserId") Long userId
    ) {
        AuctionResultResponse response = auctionResultQueryService.getResult(auctionId, userId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // #56-2: NOT_AWARDEE/ALREADY_PAID/PAYMENT_EXPIRED 판정, Order CANCELED 전이, FORFEITED
    // penalty 기록, 차순위 BackupOffer 생성이 전부 AuctionForfeitService 안에서 한 트랜잭션으로
    // 처리된다(Auction FOR UPDATE -> Order FOR UPDATE 순서). 이미 forfeit 처리된 주문에 대한
    // 재호출은 새 에러 없이 동일한 200을 그대로 반환한다(state-idempotent, 사용자 확정 - Idempotency-Key
    // 기반이 아니다. §0.11 필수 목록에 이 endpoint가 없다).
    @Operation(
            summary = "낙찰 포기",
            description = "낙찰자가 구매를 포기한다. PAYMENT_PENDING Order를 CANCELED로 전이시키고 FORFEITED penalty를 "
                    + "1건 기록한 뒤, 차순위(rank 2) 후보가 있으면 BackupOffer를 1건 생성한다. 이미 포기 처리된 주문에 "
                    + "다시 호출해도 동일한 성공 응답을 그대로 반환한다(부작용 재실행 없음)."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "포기 성공(또는 이미 처리된 상태의 재확인)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "낙찰자가 아님(40303)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 경매(40401)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "결제 기한 만료(40910) / 이미 결제 완료(40914)")
    })
    @PostMapping("/{auctionId}/award/forfeit")
    public ResponseEntity<ApiResponse<AuctionForfeitResponse>> forfeit(
            @PathVariable Long auctionId,
            @RequestAttribute("currentUserId") Long userId
    ) {
        AuctionForfeitResponse response = auctionForfeitService.forfeit(auctionId, userId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // 개인화 필드(isMine/canBid/myAutoBidStatus 등)가 있어 상세조회와 달리 인증을 필수로 건다.
    @GetMapping("/{auctionId}/live")
    public ResponseEntity<ApiResponse<AuctionLiveResponse>> getLiveView(
            @PathVariable Long auctionId,
            @RequestAttribute("currentUserId") Long userId
    ) {
        AuctionLiveResponse response = auctionQueryService.getLiveView(auctionId, userId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // 응답 자체는 사용자별로 달라지지 않지만, 계약상 인증이 필수이므로 currentUserId 파라미터로 검증을 건다.
    @GetMapping("/{auctionId}/auto-bid/recommendation")
    public ResponseEntity<ApiResponse<AutoBidRecommendationResponse>> getAutoBidRecommendation(
            @PathVariable Long auctionId,
            @RequestAttribute("currentUserId") Long userId
    ) {
        AutoBidRecommendationResponse response = auctionQueryService.getAutoBidRecommendation(auctionId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // #55: 상세조회와 동일하게 비로그인 접근을 허용한다 - 헤더가 있으면 isMine을 개인화한다.
    @GetMapping("/{auctionId}/bids")
    public ResponseEntity<ApiResponse<BidHistoryResponse>> getBidHistory(
            @PathVariable Long auctionId,
            @RequestHeader(value = "X-User-Id", required = false) Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "latest") String order
    ) {
        BidHistoryResponse response = bidQueryService.getBidHistory(auctionId, userId, page, size, order);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(
            summary = "비슷한 상품 (상세 화면 추천상품 영역)",
            description = "같은 브랜드의 노출 가능한(LIVE/SCHEDULED) 다른 경매를 종료 임박순(동률이면 "
                    + "경매 ID 오름차순으로 deterministic tie-break)으로 최대 4건 반환한다(자기 자신 제외). "
                    + "AI/embedding 추천이 아니라 same-brand heuristic이다 - 향후 추천 품질 개선 시 "
                    + "선정 로직만 교체될 수 있다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 경매(40401)")
    })
    @GetMapping("/{auctionId}/similar")
    public ResponseEntity<ApiResponse<SimilarAuctionsResponse>> getSimilarAuctions(
            @PathVariable Long auctionId,
            @RequestHeader(value = "X-User-Id", required = false) Long userId
    ) {
        SimilarAuctionsResponse response = auctionQueryService.getSimilarAuctions(auctionId, userId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(summary = "관심 상품 등록", description = "이미 등록돼 있으면 재등록 없이 현재 상태(liked=true)를 그대로 반환한다(멱등). Idempotency-Key를 요구하지 않는다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "등록 성공(또는 이미 등록된 상태)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 경매(40401)")
    })
    @PostMapping("/{auctionId}/likes")
    public ResponseEntity<ApiResponse<LikeResponse>> like(
            @PathVariable Long auctionId,
            @RequestAttribute("currentUserId") Long userId
    ) {
        LikeResponse response = auctionLikeService.like(auctionId, userId);
        // 찜은 조회보다 강한 취향 신호다(가중치 2.0). productId는 입찰 경로와 마찬가지로
        // null로 두고, 유저 벡터를 만들 때 auctionId로 한 번에 찾는다.
        activityLogService.recordLike(userId, auctionId, null);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(summary = "관심 상품 해제", description = "이미 해제돼 있거나 등록한 적이 없어도 에러 없이 현재 상태(liked=false)를 그대로 반환한다(멱등). Idempotency-Key를 요구하지 않는다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "해제 성공(또는 이미 해제된 상태)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 경매(40401)")
    })
    @DeleteMapping("/{auctionId}/likes")
    public ResponseEntity<ApiResponse<LikeResponse>> unlike(
            @PathVariable Long auctionId,
            @RequestAttribute("currentUserId") Long userId
    ) {
        LikeResponse response = auctionLikeService.unlike(auctionId, userId);
        // 찜은 상태라서 해제하면 기록도 지운다. 남겨두면 이미 관심을 거둔 상품 쪽으로
        // 취향 벡터가 계속 기운다.
        activityLogService.removeLike(userId, auctionId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(summary = "수동 입찰", description = "동일 Idempotency-Key로 재시도해도 새 입찰이 생성되지 않고 최초 성공 결과가 반환된다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "입찰 성공 또는 동일 요청 replay"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "판매자 본인 입찰(40301) 또는 입찰 제한 기간(40302)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "최고입찰자 재입찰(40901) / 미시작(40902) / 종료(40903) / 최소금액 미만(40904) / 배수 정렬 실패(40913) / Idempotency payload mismatch(40905) / 동시성 충돌(40909, 재시도 가능)")
    })
    @PostMapping("/{auctionId}/bids")
    public ResponseEntity<ApiResponse<PlaceBidResponse>> placeBid(
            @Parameter(description = "입찰 대상 경매 ID") @PathVariable Long auctionId,
            @RequestAttribute("currentUserId") Long userId,
            @Parameter(description = "요청 재시도 식별용 키. 동일 (user, auction, key)는 같은 요청으로 취급된다.", required = true)
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PlaceBidRequest request
    ) {
        PlaceBidResponse response = manualBidService.placeBid(auctionId, userId, request.amount(), idempotencyKey);
        // 입찰은 가장 강한 취향 신호다. productId는 여기서 추가 조회하지 않고 null로 두며,
        // 유저 벡터를 만들 때 auctionId로 상품을 찾는다 - 입찰 경로에 조회 쿼리를 더하지 않기 위함이다.
        activityLogService.recordBid(userId, auctionId, null);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    @Operation(
            summary = "자동입찰 등록",
            description = "LIVE 중 등록이면 ProxyPriceEngine의 실제 가격 경쟁 결과에 따라 bidOccurred/"
                    + "resultingBidAmount/isHighestBidder가 채워진다(SCHEDULED 등록은 가격에 영향이 없어 "
                    + "항상 false/null/false). 종료 1분 이내에 entrant 자신의 AUTO Bid가 실제 발생하면(bidOccurred=true) "
                    + "종료 시각이 3분 연장된다(최대 3회). 동일 Idempotency-Key로 재시도해도 새로 생성되지 않고 "
                    + "최초 성공 결과가 반환된다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "등록 성공 또는 동일 요청 replay"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "판매자 본인(40301) 또는 입찰 제한 기간(40302)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "상한가 미달(40906) / 기존 설정 존재(40908) / 종료된 경매(40903) / Idempotency payload mismatch(40905) / 동시성 충돌(40909, 재시도 가능)")
    })
    @PostMapping("/{auctionId}/auto-bids")
    public ResponseEntity<ApiResponse<AutoBidRegisterResponse>> createAutoBid(
            @PathVariable Long auctionId,
            @RequestAttribute("currentUserId") Long userId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody AutoBidMaxAmountRequest request
    ) {
        AutoBidRegisterResponse response = autoBidService.createAutoBid(auctionId, userId, request.maxAmount(), idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    @GetMapping("/{auctionId}/auto-bids/me")
    public ResponseEntity<ApiResponse<AutoBidMeResponse>> getMyAutoBid(
            @PathVariable Long auctionId,
            @RequestAttribute("currentUserId") Long userId
    ) {
        AutoBidMeResponse response = autoBidQueryService.getMyAutoBid(auctionId, userId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(
            summary = "자동입찰 상한가 수정",
            description = "LIVE 중 수정이면 ProxyPriceEngine의 실제 가격 경쟁 결과에 따라 bidOccurred/"
                    + "resultingBidAmount/isHighestBidder가 채워진다. CAP_REACHED는 cap을 올려서 실제로 경쟁에서 "
                    + "이겨야만(bidOccurred=true) 이 응답에서 ACTIVE로 복귀한다 - 단순히 cap만 올린다고 복귀하지 않는다. "
                    + "종료 1분 이내에 bidOccurred=true이면 종료 시각이 3분 연장된다(최대 3회). 동일 Idempotency-Key로 "
                    + "재시도해도 다시 처리되지 않고 최초 성공 결과가 반환된다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "수정 성공 또는 동일 요청 replay"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "등록된 자동입찰 없음(40404)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "상한가 미달(40906) / 상향하지 않음(40907, ACTIVE/CAP_REACHED만) / 종료된 경매(40903) / Idempotency payload mismatch(40905) / 동시성 충돌(40909, 재시도 가능)")
    })
    @PatchMapping("/{auctionId}/auto-bids/me")
    public ResponseEntity<ApiResponse<AutoBidUpdateResponse>> updateAutoBid(
            @PathVariable Long auctionId,
            @RequestAttribute("currentUserId") Long userId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody AutoBidMaxAmountRequest request
    ) {
        AutoBidUpdateResponse response = autoBidService.updateAutoBid(auctionId, userId, request.maxAmount(), idempotencyKey);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // row를 삭제하지 않고 CANCELED로 전이한다. Idempotency-Key를 요구하지 않는다(§0.11) - 재요청 시
    // 이미 현재 설정이 없으므로(CANCELED는 activeSlot=null) 40404로 응답한다.
    @Operation(summary = "자동입찰 중단 / 예약 취소", description = "row를 삭제하지 않고 상태를 CANCELED로 변경한다. 기존에 발생한 Bid는 삭제하지 않는다. Idempotency-Key를 요구하지 않는다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "취소 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "등록된 자동입찰 없음(40404)")
    })
    @DeleteMapping("/{auctionId}/auto-bids/me")
    public ResponseEntity<ApiResponse<AutoBidCancelResponse>> cancelAutoBid(
            @PathVariable Long auctionId,
            @RequestAttribute("currentUserId") Long userId
    ) {
        AutoBidCancelResponse response = autoBidService.cancelAutoBid(auctionId, userId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
