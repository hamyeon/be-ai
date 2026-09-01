package com.vintic.backend.backupoffer.service;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.backupoffer.domain.BackupOffer;
import com.vintic.backend.backupoffer.domain.BackupOfferStatus;
import com.vintic.backend.backupoffer.repository.BackupOfferRepository;
import com.vintic.backend.bid.domain.Bid;
import com.vintic.backend.bid.repository.BidRepository;
import com.vintic.backend.user.domain.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

// FINAL contract §15/§0.10, #57-2. lock 순서는 BackupOfferCommandService.lockOfferViaAuctionFirst()와
// 동일하다: offerId -> auctionId 식별용 non-locking 조회 -> Auction FOR UPDATE -> BackupOffer
// FOR UPDATE(재조회, authoritative) -> validation -> state transition -> 다음 BackupOffer 생성 ->
// commit. 별도 메서드로 추출하지 않고 여기서도 같은 순서를 그대로 코드화한다 - accept/decline과
// 서로 다른 auction에 대해 동시에 들어와도 항상 직렬화된다.
//
// 사용자 확정(#57-2): BackupOffer 만료 자체에는 Penalty를 만들지 않는다 - User도 건드리지 않는다
// (Order 만료와 달리 이 서비스는 User/PenaltyRepository를 의존하지 않는다).
@Service
public class BackupOfferExpirationService {

    private final AuctionRepository auctionRepository;
    private final BackupOfferRepository backupOfferRepository;
    private final BidRepository bidRepository;
    private final Clock clock;

    public BackupOfferExpirationService(
            AuctionRepository auctionRepository,
            BackupOfferRepository backupOfferRepository,
            BidRepository bidRepository,
            Clock clock
    ) {
        this.auctionRepository = auctionRepository;
        this.backupOfferRepository = backupOfferRepository;
        this.bidRepository = bidRepository;
        this.clock = clock;
    }

    @Transactional
    public void expireIfDue(Long backupOfferId) {
        Long auctionId = backupOfferRepository.findAuctionIdById(backupOfferId).orElse(null);
        if (auctionId == null) {
            return;
        }

        Auction auction = auctionRepository.findByIdForUpdate(auctionId).orElse(null);
        if (auction == null) {
            return;
        }

        BackupOffer offer = backupOfferRepository.findByIdForUpdate(backupOfferId).orElse(null);
        if (offer == null) {
            return;
        }

        LocalDateTime now = LocalDateTime.now(clock);
        if (offer.getStatus() != BackupOfferStatus.WAITING || !offer.isExpired(now)) {
            // 스케줄러가 non-locking으로 고른 후보가 이 락 사이에 이미 accept/decline됐거나,
            // 아직 기한 전이다 - 조용히 넘어간다(재실행 시 중복 처리 없음의 핵심 방어선).
            return;
        }

        offer.expire();

        createNextBackupOfferIfCandidateExists(offer);
    }

    // decline()의 다음 순위 생성 로직과 동일하다(BackupOfferCommandService.
    // createNextBackupOfferIfCandidateExists 참고) - 새 순위 정책을 만들지 않고
    // BackupCandidateSelector를 그대로 재사용한다. rank2 만료 -> rank3, rank3 만료 -> 없음.
    private void createNextBackupOfferIfCandidateExists(BackupOffer expiredOffer) {
        Auction auction = expiredOffer.getAuction();
        List<Bid> ranked = bidRepository.findLatestBidPerUserOrderedByRank(auction.getId());

        int expiredRank = BackupCandidateSelector.rankOf(ranked, expiredOffer.getCandidate().getId())
                .orElseThrow(() -> new IllegalStateException(
                        "차순위 후보의 순위를 찾을 수 없습니다. backupOfferId: " + expiredOffer.getId()
                ));

        BackupCandidateSelector.next(ranked, expiredRank).ifPresent(candidateBid -> {
            User nextCandidate = candidateBid.getUser();
            if (backupOfferRepository.findByAuctionIdAndCandidateId(auction.getId(), nextCandidate.getId()).isPresent()) {
                // 방어적 중복 방지 - uk_backup_offer_auction_candidate가 최종 방어선이다.
                return;
            }
            backupOfferRepository.save(BackupOffer.create(auction, nextCandidate, candidateBid.getAmount()));
        });
    }
}
