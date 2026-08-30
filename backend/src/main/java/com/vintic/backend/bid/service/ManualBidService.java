package com.vintic.backend.bid.service;

import com.vintic.backend.bid.dto.PlaceBidResponse;
import org.springframework.stereotype.Service;

// Controller의 얇은 진입점이다. @Transactional을 직접 갖지 않고, 실제 트랜잭션 경계는
// IdempotencyClaimService의 제네릭 메서드가 갖는다 - 이 클래스는 그 둘을 프록시를 통해
// 호출하는 오케스트레이션만 담당한다.
@Service
public class ManualBidService {

    private final IdempotencyClaimService idempotencyClaimService;
    private final BidCommandService bidCommandService;

    public ManualBidService(IdempotencyClaimService idempotencyClaimService, BidCommandService bidCommandService) {
        this.idempotencyClaimService = idempotencyClaimService;
        this.bidCommandService = bidCommandService;
    }

    public PlaceBidResponse placeBid(Long auctionId, Long userId, Long amount, String idempotencyKey) {
        String operationScope = "PLACE_BID:" + auctionId;
        String requestHash = BidRequestHash.sha256(amount);

        try {
            return idempotencyClaimService.claimAndExecute(
                    userId, operationScope, idempotencyKey, requestHash,
                    PlaceBidResponse.class,
                    idempotencyId -> bidCommandService.placeManualBid(auctionId, userId, amount, idempotencyId)
            );
        } catch (IdempotencyClaimConflictException e) {
            return idempotencyClaimService.resolveAfterConflict(
                    userId, operationScope, idempotencyKey, requestHash, PlaceBidResponse.class
            );
        }
    }
}
