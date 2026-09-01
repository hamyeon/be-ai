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

// #73-3: AuctionStartScheduler와 동일한 구조. 후보 조회 시점의 endAt은 순수 선별용이다 -
// AuctionEndService.endIfDue()에 그 값을 넘기지 않고 auctionId만 넘긴다. endIfDue()가 락 이후
// 다시 읽은 "최신" endAt(연장 반영)만 authoritative하게 재확인한다(#73-2가 이미 확립한 원칙,
// 여기서 재구현하지 않는다).
@Component
@Slf4j
public class AuctionEndScheduler {

    private final AuctionRepository auctionRepository;
    private final AuctionEndService auctionEndService;
    private final Clock clock;
    private final boolean enabled;
    private final int batchSize;

    public AuctionEndScheduler(
            AuctionRepository auctionRepository,
            AuctionEndService auctionEndService,
            Clock clock,
            @Value("${auction.lifecycle.end.enabled:false}") boolean enabled,
            @Value("${auction.lifecycle.batch-size:100}") int batchSize
    ) {
        this.auctionRepository = auctionRepository;
        this.auctionEndService = auctionEndService;
        this.clock = clock;
        this.enabled = enabled;
        this.batchSize = batchSize;
    }

    @Scheduled(cron = "${auction.lifecycle.end.cron:0 * * * * *}")
    public void endDueAuctions() {
        if (!enabled) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        List<Long> candidateIds = auctionRepository.findLiveDueForEnd(
                AuctionStatus.LIVE, now, PageRequest.of(0, batchSize)
        );

        int failed = 0;
        for (Long auctionId : candidateIds) {
            try {
                auctionEndService.endIfDue(auctionId);
            } catch (RuntimeException e) {
                failed++;
                log.warn("Auction 종료 처리에 실패했습니다. auctionId={}, message={}", auctionId, e.getMessage());
            }
        }
        if (!candidateIds.isEmpty()) {
            log.info(
                    "Auction 종료 처리를 시도했습니다. candidates={}, success={}, failed={}",
                    candidateIds.size(), candidateIds.size() - failed, failed
            );
        }
    }
}
