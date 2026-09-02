package com.vintic.backend.bid.service;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.bid.dto.PlaceBidResponse;
import com.vintic.backend.common.exception.AuctionNotFoundException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * #74 실험 전용(experiment/#74-optimistic-lock-retry): production wiring에는 존재하지 않는
 * test-scoped 컴포넌트다(harness가 필요한 IT에서 {@code @TestConfiguration}으로 명시 등록해서
 * 쓴다 - #34/#35의 {@code DelayConfig} 패턴과 동일하게 component-scan에 기대지 않는다).
 *
 * production {@link BidCommandService#placeManualBid(Long, Long, Long, Long)}과 유일하게
 * 다른 점은 최초 조회가 {@code findByIdForUpdate()}(PESSIMISTIC_WRITE)가 아니라 non-locking
 * {@code findById()}라는 것 하나뿐이다. 그 이후 business logic은
 * {@link BidCommandService#executeManualBidOnLoadedAuction}을 그대로 재사용해 복제하지
 * 않는다 - 이 메서드가 매 attempt마다 방금 조회한 "최신" Auction으로 검증부터 다시 실행되므로
 * revalidation은 이 재사용 자체로 보장된다(캐시/재사용되는 계산값 없음).
 *
 * REQUIRES_NEW로 명시하는 이유: 이 서비스는 항상 {@link OptimisticBidRetryOrchestrator}(비
 * 트랜잭션)처럼 트랜잭션이 없는 caller에서 호출되는 것을 전제하지만, 호출자가 우연히 트랜잭션
 * 안에 있더라도 "매 attempt = 새 물리 트랜잭션"이라는 §74 설계 불변식이 깨지지 않도록 방어적으로
 * 고정한다.
 */
public class OptimisticBidAttemptService implements ManualBidAttemptExecutor {

    private final AuctionRepository auctionRepository;
    private final BidCommandService bidCommandService;

    public OptimisticBidAttemptService(AuctionRepository auctionRepository, BidCommandService bidCommandService) {
        this.auctionRepository = auctionRepository;
        this.bidCommandService = bidCommandService;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PlaceBidResponse attempt(Long auctionId, Long userId, Long amount, Long idempotencyId) {
        Auction auction = auctionRepository.findById(auctionId)
                .orElseThrow(() -> new AuctionNotFoundException("존재하지 않는 경매입니다. auctionId: " + auctionId));
        return bidCommandService.executeManualBidOnLoadedAuction(auction, userId, amount, idempotencyId);
    }
}
