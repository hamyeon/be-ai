package com.vintic.backend.auction;

import com.vintic.backend.auction.dto.AuctionDetailResponse;
import com.vintic.backend.auction.service.AuctionQueryService;
import com.vintic.backend.bid.dto.BidHistoryResponse;
import com.vintic.backend.bid.dto.PlaceBidRequest;
import com.vintic.backend.bid.dto.PlaceBidResponse;
import com.vintic.backend.bid.service.BidQueryService;
import com.vintic.backend.bid.service.ManualBidService;
import com.vintic.backend.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auctions")
public class AuctionController {

    private final AuctionQueryService auctionQueryService;
    private final BidQueryService bidQueryService;
    private final ManualBidService manualBidService;

    public AuctionController(
            AuctionQueryService auctionQueryService,
            BidQueryService bidQueryService,
            ManualBidService manualBidService
    ) {
        this.auctionQueryService = auctionQueryService;
        this.bidQueryService = bidQueryService;
        this.manualBidService = manualBidService;
    }

    @GetMapping("/{auctionId}")
    public ResponseEntity<ApiResponse<AuctionDetailResponse>> getAuction(@PathVariable Long auctionId) {
        AuctionDetailResponse response = auctionQueryService.getAuctionDetail(auctionId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/{auctionId}/bids")
    public ResponseEntity<ApiResponse<BidHistoryResponse>> getBidHistory(
            @PathVariable Long auctionId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "latest") String order
    ) {
        BidHistoryResponse response = bidQueryService.getBidHistory(auctionId, page, size, order);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(summary = "수동 입찰", description = "동일 Idempotency-Key로 재시도해도 새 입찰이 생성되지 않고 최초 성공 결과가 반환된다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "입찰 성공 또는 동일 요청 replay"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "판매자 본인 입찰(40301) 또는 입찰 제한 기간(40302)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "최고입찰자 재입찰(40901) / 미시작(40902) / 종료(40903) / 최소금액 미만(40904) / Idempotency payload mismatch(40905)")
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
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }
}
