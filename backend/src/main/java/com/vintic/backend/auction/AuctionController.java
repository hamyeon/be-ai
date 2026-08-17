package com.vintic.backend.auction;

import com.vintic.backend.auction.dto.AuctionDetailResponse;
import com.vintic.backend.auction.service.AuctionQueryService;
import com.vintic.backend.bid.dto.BidHistoryResponse;
import com.vintic.backend.bid.dto.PlaceBidRequest;
import com.vintic.backend.bid.dto.PlaceBidResponse;
import com.vintic.backend.bid.service.BidCommandService;
import com.vintic.backend.bid.service.BidQueryService;
import com.vintic.backend.common.dto.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auctions")
public class AuctionController {

    private final AuctionQueryService auctionQueryService;
    private final BidQueryService bidQueryService;
    private final BidCommandService bidCommandService;

    public AuctionController(
            AuctionQueryService auctionQueryService,
            BidQueryService bidQueryService,
            BidCommandService bidCommandService
    ) {
        this.auctionQueryService = auctionQueryService;
        this.bidQueryService = bidQueryService;
        this.bidCommandService = bidCommandService;
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

    @PostMapping("/{auctionId}/bids")
    public ResponseEntity<ApiResponse<PlaceBidResponse>> placeBid(
            @PathVariable Long auctionId,
            @RequestAttribute("currentUserId") Long userId,
            @Valid @RequestBody PlaceBidRequest request
    ) {
        PlaceBidResponse response = bidCommandService.placeManualBid(auctionId, userId, request.amount());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }
}
