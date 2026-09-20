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
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

// #73-3: AuctionStartScheduler와 동일한 구조. 후보 조회 시점의 endAt은 순수 선별용이다 -
// AuctionEndService.endIfDue()에 그 값을 넘기지 않고 auctionId만 넘긴다. endIfDue()가 락 이후
// 다시 읽은 "최신" endAt(연장 반영)만 authoritative하게 재확인한다(#73-2가 이미 확립한 원칙,
// 여기서 재구현하지 않는다).
//
// Day12: 합계 ended가 실제 종료 건수, notLive는 다른 인스턴스가 먼저 처리한 건수다 - API
// 서버가 여러 대일 때 같은 후보를 두 인스턴스가 동시에 조회할 수 있고, 늦게 lock을 얻은
// 쪽은 재확인 시점에 이미 ENDED라 아무것도 하지 않는다(AuctionEndOutcome 참고).
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
        runOnce();
    }

    EndRunSummary runOnce() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<Long> candidateIds = auctionRepository.findLiveDueForEnd(
                AuctionStatus.LIVE, now, PageRequest.of(0, batchSize)
        );

        Map<AuctionEndOutcome, Integer> counts = new EnumMap<>(AuctionEndOutcome.class);
        int failed = 0;
        for (Long auctionId : candidateIds) {
            try {
                AuctionEndOutcome outcome = auctionEndService.endIfDue(auctionId);
                if (outcome != null) {
                    counts.merge(outcome, 1, Integer::sum);
                }
            } catch (RuntimeException e) {
                failed++;
                log.warn("Auction 종료 처리에 실패했습니다. auctionId={}, message={}", auctionId, e.getMessage());
            }
        }

        EndRunSummary summary = new EndRunSummary(
                candidateIds.size(),
                counts.getOrDefault(AuctionEndOutcome.ENDED, 0),
                counts.getOrDefault(AuctionEndOutcome.NOT_LIVE, 0),
                counts.getOrDefault(AuctionEndOutcome.NOT_DUE, 0),
                counts.getOrDefault(AuctionEndOutcome.NOT_FOUND, 0),
                failed
        );
        if (!candidateIds.isEmpty()) {
            log.info(
                    "Auction 종료 처리를 시도했습니다. candidates={}, ended={}, notLive={}, notDue={}, notFound={}, failed={}",
                    summary.candidates(), summary.ended(), summary.notLive(), summary.notDue(), summary.notFound(), summary.failed()
            );
        }
        return summary;
    }

    record EndRunSummary(int candidates, int ended, int notLive, int notDue, int notFound, int failed) {
    }
}
