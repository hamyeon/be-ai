package com.vintic.backend.auction;

import com.vintic.backend.auction.dto.AuctionDetailResponse;
import com.vintic.backend.auction.service.AuctionQueryService;
import com.vintic.backend.bid.dto.BidHistoryResponse;
import com.vintic.backend.bid.service.BidQueryService;
import com.vintic.backend.common.dto.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auctions")
public class AuctionController {

    private final AuctionQueryService auctionQueryService;
    private final BidQueryService bidQueryService;

    public AuctionController(AuctionQueryService auctionQueryService, BidQueryService bidQueryService) {
        this.auctionQueryService = auctionQueryService;
        this.bidQueryService = bidQueryService;
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
}
