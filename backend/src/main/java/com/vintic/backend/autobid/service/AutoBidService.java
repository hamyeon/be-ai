package com.vintic.backend.autobid.service;

import com.vintic.backend.autobid.dto.AutoBidCancelResponse;
import com.vintic.backend.autobid.dto.AutoBidRegisterResponse;
import com.vintic.backend.autobid.dto.AutoBidUpdateResponse;
import com.vintic.backend.bid.service.IdempotencyClaimConflictException;
import com.vintic.backend.bid.service.IdempotencyClaimService;
import org.springframework.stereotype.Service;

// Controller의 얇은 진입점이다(ManualBidService와 동일한 역할 분담). @Transactional을 직접
// 갖지 않고, 실제 트랜잭션 경계는 IdempotencyClaimService의 제네릭 메서드가 갖는다.
@Service
public class AutoBidService {

    private final IdempotencyClaimService idempotencyClaimService;
    private final AutoBidCommandService autoBidCommandService;

    public AutoBidService(
            IdempotencyClaimService idempotencyClaimService,
            AutoBidCommandService autoBidCommandService
    ) {
        this.idempotencyClaimService = idempotencyClaimService;
        this.autoBidCommandService = autoBidCommandService;
    }

    public AutoBidRegisterResponse createAutoBid(Long auctionId, Long userId, Long maxAmount, String idempotencyKey) {
        String operationScope = "CREATE_AUTO_BID:" + auctionId;
        String requestHash = AutoBidRequestHash.sha256(maxAmount);

        try {
            return idempotencyClaimService.claimAndExecute(
                    userId, operationScope, idempotencyKey, requestHash,
                    AutoBidRegisterResponse.class,
                    idempotencyId -> autoBidCommandService.createAutoBid(auctionId, userId, maxAmount, idempotencyId)
            );
        } catch (IdempotencyClaimConflictException e) {
            return idempotencyClaimService.resolveAfterConflict(
                    userId, operationScope, idempotencyKey, requestHash, AutoBidRegisterResponse.class
            );
        }
    }

    public AutoBidUpdateResponse updateAutoBid(Long auctionId, Long userId, Long newMaxAmount, String idempotencyKey) {
        String operationScope = "UPDATE_AUTO_BID:" + auctionId;
        String requestHash = AutoBidRequestHash.sha256(newMaxAmount);

        try {
            return idempotencyClaimService.claimAndExecute(
                    userId, operationScope, idempotencyKey, requestHash,
                    AutoBidUpdateResponse.class,
                    idempotencyId -> autoBidCommandService.updateAutoBid(auctionId, userId, newMaxAmount, idempotencyId)
            );
        } catch (IdempotencyClaimConflictException e) {
            return idempotencyClaimService.resolveAfterConflict(
                    userId, operationScope, idempotencyKey, requestHash, AutoBidUpdateResponse.class
            );
        }
    }

    // DELETE는 계약상(§0.11) Idempotency-Key를 요구하지 않는다 - idempotency 경로를 타지 않고
    // 바로 실행한다. 재요청 시 이미 CANCELED(=현재 설정 없음)이므로 자연스럽게 40404가 된다.
    public AutoBidCancelResponse cancelAutoBid(Long auctionId, Long userId) {
        return autoBidCommandService.cancelAutoBid(auctionId, userId);
    }
}
