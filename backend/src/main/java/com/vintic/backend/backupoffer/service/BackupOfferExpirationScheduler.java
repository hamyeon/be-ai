package com.vintic.backend.backupoffer.service;

import com.vintic.backend.backupoffer.domain.BackupOfferStatus;
import com.vintic.backend.backupoffer.repository.BackupOfferRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

// FINAL contract §15/§0.10, #57-2(#56 deferred). OrderExpirationScheduler와 동일한 구조 -
// 후보 id 조회(non-locking)와 실제 전이(BackupOfferExpirationService.expireIfDue(), 건당 별도
// 트랜잭션)를 분리해 한 건의 실패가 같은 회차의 다른 건 처리를 막지 않게 한다.
@Component
@Slf4j
public class BackupOfferExpirationScheduler {

    private final BackupOfferRepository backupOfferRepository;
    private final BackupOfferExpirationService backupOfferExpirationService;
    private final Clock clock;
    private final boolean enabled;

    public BackupOfferExpirationScheduler(
            BackupOfferRepository backupOfferRepository,
            BackupOfferExpirationService backupOfferExpirationService,
            Clock clock,
            @Value("${backup-offer.expiration.enabled:true}") boolean enabled
    ) {
        this.backupOfferRepository = backupOfferRepository;
        this.backupOfferExpirationService = backupOfferExpirationService;
        this.clock = clock;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${backup-offer.expiration.cron:0 * * * * *}")
    public void expirePastDueOffers() {
        if (!enabled) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        List<Long> candidateIds = backupOfferRepository.findExpiredWaitingOfferIds(BackupOfferStatus.WAITING, now);
        for (Long offerId : candidateIds) {
            try {
                backupOfferExpirationService.expireIfDue(offerId);
            } catch (RuntimeException e) {
                log.warn("BackupOffer 만료 처리에 실패했습니다. backupOfferId={}, message={}", offerId, e.getMessage());
            }
        }
        if (!candidateIds.isEmpty()) {
            log.info("BackupOffer 만료 처리를 시도했습니다. count={}", candidateIds.size());
        }
    }
}
