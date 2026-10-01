package com.vintic.backend.backupoffer.service;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.backupoffer.domain.BackupOffer;
import com.vintic.backend.backupoffer.domain.BackupOfferStatus;
import com.vintic.backend.backupoffer.dto.BackupOfferAcceptResponse;
import com.vintic.backend.backupoffer.dto.BackupOfferDeclineResponse;
import com.vintic.backend.backupoffer.repository.BackupOfferRepository;
import com.vintic.backend.bid.domain.Bid;
import com.vintic.backend.bid.repository.BidRepository;
import com.vintic.backend.common.exception.AuctionNotFoundException;
import com.vintic.backend.common.exception.BackupOfferAccessDeniedException;
import com.vintic.backend.common.exception.BackupOfferAlreadyResolvedException;
import com.vintic.backend.common.exception.BackupOfferExpiredException;
import com.vintic.backend.common.exception.BackupOfferNotFoundException;
import com.vintic.backend.common.util.ShippingPolicy;
import com.vintic.backend.common.util.TimePolicy;
import com.vintic.backend.notification.domain.NotificationType;
import com.vintic.backend.notification.service.NotificationRecorder;
import com.vintic.backend.order.domain.Order;
import com.vintic.backend.order.repository.OrderRepository;
import com.vintic.backend.user.domain.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

// FINAL contract §16-17. lock 순서(#56 종료 전 확정): offerId -> auctionId 식별용 non-locking
// 조회 -> Auction FOR UPDATE -> BackupOffer FOR UPDATE(재조회, authoritative) -> validation ->
// state transition -> Order/다음 BackupOffer 생성 -> commit. AuctionForfeitService(forfeit)/
// AuctionSettlementService(settle)와 "Auction을 첫 authoritative lock으로 쓴다"는 원칙을 여기서도
// 통일한다 - 이 두 row를 동시에 잠그는 경로가 앞으로 늘어나도 lock ordering이 항상
// Auction -> BackupOffer로 고정돼 있어 lock-order inversion에 의한 데드락 경로가 생기지 않는다
// (#45/#46이 확립한 pessimistic locking 원칙 유지).
//
// 최초 offerId -> auctionId 조회(findAuctionIdById)는 식별 전용이다 - 그 시점에 얻는 값은
// auctionId 하나뿐이고, status/deadline/candidate 같은 business decision에는 절대 쓰지 않는다.
// 이 non-locking 조회가 트랜잭션의 REPEATABLE READ snapshot을 먼저 고정시킬 수 있지만(#46
// follow-up과 동일한 이유), 그 뒤의 Auction/BackupOffer 조회는 둘 다 locking read라 snapshot과
// 무관하게 항상 최신 커밋을 본다 - Auction lock 이후 재조회하는 BackupOffer만이 실제
// authoritative 값이다(lockOfferViaAuctionFirst() 참고).
//
// #75: accept/decline 모두 candidate 본인 여부를 검증한다(40305 BACKUP_OFFER_ACCESS_DENIED) -
// #56~#57까지는 계약 침묵을 이유로 검증하지 않았으나 이번에 계약을 확장해 해소했다. 검증 순서는
// lockOfferViaAuctionFirst()의 404 → ownership(403) → 기존 상태/기한(409) 순으로 고정한다
// (OrderQueryService의 404 → 403 순서와 동일 원칙).
@Service
public class BackupOfferCommandService {

    private final AuctionRepository auctionRepository;
    private final BackupOfferRepository backupOfferRepository;
    private final OrderRepository orderRepository;
    private final BidRepository bidRepository;
    private final Clock clock;
    private final NotificationRecorder notificationRecorder;

    public BackupOfferCommandService(
            AuctionRepository auctionRepository,
            BackupOfferRepository backupOfferRepository,
            OrderRepository orderRepository,
            BidRepository bidRepository,
            Clock clock,
            NotificationRecorder notificationRecorder
    ) {
        this.auctionRepository = auctionRepository;
        this.backupOfferRepository = backupOfferRepository;
        this.orderRepository = orderRepository;
        this.bidRepository = bidRepository;
        this.clock = clock;
        this.notificationRecorder = notificationRecorder;
    }

    @Transactional
    public BackupOfferAcceptResponse accept(Long backupOfferId, Long userId) {
        BackupOffer offer = lockOfferViaAuctionFirst(backupOfferId);

        if (!offer.isOwnedBy(userId)) {
            throw new BackupOfferAccessDeniedException(
                    "본인 명의의 차순위 제안이 아닙니다. backupOfferId: " + backupOfferId
            );
        }

        if (offer.getStatus() != BackupOfferStatus.WAITING) {
            throw new BackupOfferAlreadyResolvedException("이미 처리된 제안입니다. backupOfferId: " + backupOfferId);
        }

        LocalDateTime acceptedAt = LocalDateTime.now(clock);
        if (offer.isExpired(acceptedAt)) {
            throw new BackupOfferExpiredException("차순위 구매 기한이 만료되었습니다. backupOfferId: " + backupOfferId);
        }

        offer.accept();

        // paymentDeadline = 수락 시각 + 24h(§0.10) - 원 경매의 endsAt, 제안의 deadline과 모두
        // 무관하다(§16 명시). Order UNIQUE(auction_id, buyer_id)가 동일 (auction, candidate)에
        // 대한 중복 생성을 막는 최종 방어선이다 - 이 offer의 상태 가드(WAITING만 여기 도달) 자체가
        // 이미 사실상의 1차 방어선이다.
        LocalDateTime paymentDeadline = acceptedAt.plusHours(24);
        Auction auction = offer.getAuction();
        User candidate = offer.getCandidate();
        Order order = orderRepository.save(Order.createForBackupAccept(
                auction, candidate, offer.getPurchasePrice(), ShippingPolicy.FLAT_FEE, paymentDeadline
        ));

        return new BackupOfferAcceptResponse(
                offer.getId(),
                offer.getStatus(),
                order.getId(),
                order.getTotalAmount(),
                TimePolicy.toApiTime(paymentDeadline)
        );
    }

    // Idempotency-Key를 요구하지 않는다(§0.11에 이 endpoint가 없다) - 동시성 방어는 lock
    // ordering(lockOfferViaAuctionFirst)만으로 한다.
    @Transactional
    public BackupOfferDeclineResponse decline(Long backupOfferId, Long userId) {
        BackupOffer offer = lockOfferViaAuctionFirst(backupOfferId);

        if (!offer.isOwnedBy(userId)) {
            throw new BackupOfferAccessDeniedException(
                    "본인 명의의 차순위 제안이 아닙니다. backupOfferId: " + backupOfferId
            );
        }

        if (offer.getStatus() != BackupOfferStatus.WAITING) {
            throw new BackupOfferAlreadyResolvedException("이미 처리된 제안입니다. backupOfferId: " + backupOfferId);
        }

        offer.decline();

        createNextBackupOfferIfCandidateExists(offer);

        return new BackupOfferDeclineResponse(offer.getId(), offer.getStatus());
    }

    // accept/decline이 공유하는 lock 획득 진입점. 클래스 주석의 lock 순서를 그대로 코드화한다.
    private BackupOffer lockOfferViaAuctionFirst(Long backupOfferId) {
        // 1) 식별 전용(non-locking) - auctionId 하나만 얻는다. 여기서 읽은 값은 validation/state
        //    transition에 재사용하지 않는다.
        Long auctionId = backupOfferRepository.findAuctionIdById(backupOfferId)
                .orElseThrow(() -> new BackupOfferNotFoundException(
                        "존재하지 않는 차순위 제안입니다. backupOfferId: " + backupOfferId
                ));

        // 2) Auction을 먼저 잠근다 - forfeit/settlement와 동일한 lock ordering(Auction 우선).
        auctionRepository.findByIdForUpdate(auctionId)
                .orElseThrow(() -> new AuctionNotFoundException("존재하지 않는 경매입니다. auctionId: " + auctionId));

        // 3) Auction 락 확보 이후 BackupOffer를 다시 locking read로 조회한다 - 이 인스턴스만이
        //    authoritative하다(status/deadline/candidate 전부 이 값 기준으로 판정한다).
        return backupOfferRepository.findByIdForUpdate(backupOfferId)
                .orElseThrow(() -> new BackupOfferNotFoundException(
                        "존재하지 않는 차순위 제안입니다. backupOfferId: " + backupOfferId
                ));
    }

    // #56-0 확정: rank 2가 decline하면 rank 3에게, rank 3이 decline하면 더 이상 생성하지 않는다.
    // BackupCandidateSelector가 forfeit(#56-2)과 이 메서드가 공유하는 단일 선정 로직이다.
    private void createNextBackupOfferIfCandidateExists(BackupOffer declinedOffer) {
        Auction auction = declinedOffer.getAuction();
        List<Bid> ranked = bidRepository.findLatestBidPerUserOrderedByRank(auction.getId());

        int declinedRank = BackupCandidateSelector.rankOf(ranked, declinedOffer.getCandidate().getId())
                .orElseThrow(() -> new IllegalStateException(
                        "차순위 후보의 순위를 찾을 수 없습니다. backupOfferId: " + declinedOffer.getId()
                ));

        BackupCandidateSelector.next(ranked, declinedRank).ifPresent(candidateBid -> {
            User nextCandidate = candidateBid.getUser();
            if (backupOfferRepository.findByAuctionIdAndCandidateId(auction.getId(), nextCandidate.getId()).isPresent()) {
                // 방어적 중복 방지 - uk_backup_offer_auction_candidate가 최종 방어선이다.
                return;
            }
            BackupOffer saved = backupOfferRepository.save(BackupOffer.create(auction, nextCandidate, candidateBid.getAmount()));
            // #75: 신규 BackupOffer가 실제 저장된 경우에만 기록한다.
            notificationRecorder.record(nextCandidate, NotificationType.BACKUP_OFFER_CREATED, auction.getId(), saved.getId());
        });
    }
}
