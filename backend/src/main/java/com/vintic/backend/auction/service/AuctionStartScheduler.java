package com.vintic.backend.auction.service;

import com.vintic.backend.auction.domain.AuctionStatus;
import com.vintic.backend.auction.repository.AuctionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

// #73-3: 후보 id 조회(non-locking)와 실제 전이(AuctionStartService.startIfDue(), 건당 별도
// 트랜잭션)를 분리한다 - OrderExpirationScheduler/BackupOfferExpirationScheduler(#57-2)와 동일한
// 구조다. 이 클래스는 orchestration만 담당한다 - 상태 전이/AutoBid/Proxy 판단은 전부
// AuctionStartService(#73-1)에 있고 여기서 복제하지 않는다.
@Component
@Slf4j
public class AuctionStartScheduler {

    private final AuctionRepository auctionRepository;
    private final AuctionStartService auctionStartService;
    private final Clock clock;
    private final boolean enabled;
    private final int batchSize;

    public AuctionStartScheduler(
            AuctionRepository auctionRepository,
            AuctionStartService auctionStartService,
            Clock clock,
            @Value("${auction.lifecycle.start.enabled:false}") boolean enabled,
            @Value("${auction.lifecycle.batch-size:100}") int batchSize
    ) {
        this.auctionRepository = auctionRepository;
        this.auctionStartService = auctionStartService;
        this.clock = clock;
        this.enabled = enabled;
        this.batchSize = batchSize;
    }

    @Scheduled(cron = "${auction.lifecycle.start.cron:0 * * * * *}")
    public void startDueAuctions() {
        if (!enabled) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        List<Long> candidateIds = auctionRepository.findScheduledDueForStart(
                AuctionStatus.SCHEDULED, now, PageRequest.of(0, batchSize)
        );

        int failed = 0;
        for (Long auctionId : candidateIds) {
            try {
                auctionStartService.startIfDue(auctionId);
            } catch (RuntimeException e) {
                failed++;
                log.warn("Auction 시작 처리에 실패했습니다. auctionId={}, message={}", auctionId, e.getMessage());
            }
        }
        if (!candidateIds.isEmpty()) {
            log.info(
                    "Auction 시작 처리를 시도했습니다. candidates={}, success={}, failed={}",
                    candidateIds.size(), candidateIds.size() - failed, failed
            );
        }
    }
}
