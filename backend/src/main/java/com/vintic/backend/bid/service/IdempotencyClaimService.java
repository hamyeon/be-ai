package com.vintic.backend.bid.service;

import com.vintic.backend.bid.domain.Bid;
import com.vintic.backend.bid.domain.Idempotency;
import com.vintic.backend.bid.dto.PlaceBidResponse;
import com.vintic.backend.bid.repository.BidRepository;
import com.vintic.backend.bid.repository.IdempotencyRepository;
import com.vintic.backend.common.exception.IdempotencyPayloadMismatchException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

// claim + Auction/Bid 변경 + resultBidId 기록을 하나의 트랜잭션으로 묶는 계층이다.
// claimAndPlaceBid()/resolveAfterConflict()는 반드시 ManualBidService처럼 이 빈을
// 주입받아 호출하는 다른 빈에서 호출해야 한다. 같은 클래스 안에서 this로 서로를
// 호출하면 Spring 프록시를 우회해 @Transactional이 적용되지 않는다.
@Service
public class IdempotencyClaimService {

    private final IdempotencyRepository idempotencyRepository;
    private final BidRepository bidRepository;
    private final BidCommandService bidCommandService;

    public IdempotencyClaimService(
            IdempotencyRepository idempotencyRepository,
            BidRepository bidRepository,
            BidCommandService bidCommandService
    ) {
        this.idempotencyRepository = idempotencyRepository;
        this.bidRepository = bidRepository;
        this.bidCommandService = bidCommandService;
    }

    @Transactional
    public PlaceBidResponse claimAndPlaceBid(
            Long auctionId, Long userId, Long amount,
            String operationScope, String idempotencyKey, String requestHash
    ) {
        Optional<Idempotency> existing = idempotencyRepository
                .findByUserIdAndOperationScopeAndIdempotencyKey(userId, operationScope, idempotencyKey);
        if (existing.isPresent()) {
            return replayOrReject(existing.get(), requestHash);
        }

        Idempotency claim = Idempotency.claim(userId, operationScope, idempotencyKey, requestHash);
        try {
            // claim insert를 지금 실제로 DB에 반영해야 동시 요청의 UNIQUE 경쟁이 여기서 발생한다.
            // commit 시점까지 미루면 두 요청 모두 Auction/Bid까지 처리한 뒤에야 충돌을 발견하게 된다.
            idempotencyRepository.saveAndFlush(claim);
        } catch (DataIntegrityViolationException e) {
            // 이 시점 이후로는 같은 트랜잭션에서 추가 DB 작업을 하지 않는다.
            // 그대로 던져서 트랜잭션을 롤백시키고, 조회는 별도 트랜잭션(resolveAfterConflict)에 맡긴다.
            throw new IdempotencyClaimConflictException(e);
        }

        PlaceBidResponse response = bidCommandService.placeManualBid(auctionId, userId, amount);
        claim.attachResultBidId(response.bidId());
        return response;
    }

    @Transactional
    public PlaceBidResponse resolveAfterConflict(
            Long userId, String operationScope, String idempotencyKey, String requestHash
    ) {
        // UNIQUE 충돌 시점에 이긴 트랜잭션은 이미 commit되어 있음이 보장된다(InnoDB가
        // 같은 unique key의 두 번째 INSERT를 첫 트랜잭션의 commit/rollback까지 블로킹하기 때문).
        // 따라서 여기서는 재조회만으로 충분하고 재시도 루프가 필요 없다.
        Idempotency existing = idempotencyRepository
                .findByUserIdAndOperationScopeAndIdempotencyKey(userId, operationScope, idempotencyKey)
                .orElseThrow(() -> new IllegalStateException(
                        "UNIQUE 충돌 이후에도 기존 Idempotency row를 찾지 못했습니다. userId=" + userId
                                + ", operationScope=" + operationScope));
        return replayOrReject(existing, requestHash);
    }

    private PlaceBidResponse replayOrReject(Idempotency existing, String requestHash) {
        if (!existing.getRequestHash().equals(requestHash)) {
            throw new IdempotencyPayloadMismatchException(
                    "동일한 Idempotency-Key로 이전과 다른 요청 내용이 감지되었습니다."
            );
        }
        Bid bid = bidRepository.findById(existing.getResultBidId())
                .orElseThrow(() -> new IllegalStateException(
                        "Idempotency에 기록된 Bid를 찾을 수 없습니다. bidId: " + existing.getResultBidId()));
        return PlaceBidResponse.ofReplay(bid);
    }
}
