package com.vintic.backend.bid.service;

import com.vintic.backend.bid.dto.PlaceBidResponse;
import com.vintic.backend.config.CacheConfig;
import org.springframework.cache.annotation.CacheEvict;
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

    // 입찰이 들어오면 추천 Fallback 목록이 낡는다. 현재가가 바뀌고 인기 순위도 함께 밀린다.
    // allEntries = true인 이유는 캐시 키가 limit이라 특정 경매만 골라 비울 수 없어서다.
    // 목록 항목 수만큼(현재 limit 값 종류만큼)이라 통째로 비워도 부담이 없다.
    @CacheEvict(cacheNames = CacheConfig.RECOMMENDATION_FALLBACK_CACHE, allEntries = true)
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
