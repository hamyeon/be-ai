package com.vintic.backend.order.service;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.backupoffer.domain.BackupOffer;
import com.vintic.backend.backupoffer.repository.BackupOfferRepository;
import com.vintic.backend.backupoffer.service.BackupCandidateSelector;
import com.vintic.backend.bid.domain.Bid;
import com.vintic.backend.bid.repository.BidRepository;
import com.vintic.backend.notification.domain.NotificationType;
import com.vintic.backend.notification.service.NotificationRecorder;
import com.vintic.backend.order.domain.Order;
import com.vintic.backend.order.domain.OrderStatus;
import com.vintic.backend.order.repository.OrderRepository;
import com.vintic.backend.penalty.domain.Penalty;
import com.vintic.backend.penalty.domain.PenaltyType;
import com.vintic.backend.penalty.repository.PenaltyRepository;
import com.vintic.backend.penalty.service.BidRestrictionPolicy;
import com.vintic.backend.user.domain.User;
import com.vintic.backend.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

// FINAL contract §12/§0.10, #57-2. lock 순서: orderId -> auctionId 식별용 non-locking 조회 ->
// Auction FOR UPDATE -> Order FOR UPDATE(재조회, authoritative) -> validation -> state
// transition -> penalty(+User FOR UPDATE) -> 다음 BackupOffer 생성 -> commit. Auction을 먼저
// 잠그는 원칙은 AuctionForfeitService/BackupOfferCommandService와 동일하다 - forfeit/accept/
// decline이 같은 auction에 대해 동시에 들어와도 이 트랜잭션과 항상 직렬화된다. User는 항상
// Order 다음에만 잠근다(Auction -> Order -> User 고정) - 서로 다른 auction의 만료 처리가 같은
// buyer를 동시에 건드려도 각자 자신의 Auction/Order를 먼저 확보해야만 User를 요청하므로 순환
// 대기가 생기지 않는다(UserRepository.findByIdForUpdate 참고).
//
// PAID/CANCELED는 이 서비스에 도달해도 아무 일도 하지 않는다 - expireIfDue()가 락 이후 상태를
// 재확인해 PAYMENT_PENDING이 아니면 조용히 반환한다(스케줄러가 non-locking으로 고른 후보가
// stale할 수 있다는 전제, BackupOfferCommandService의 lockOfferViaAuctionFirst와 동일한 이유).
@Service
public class OrderExpirationService {

    private final AuctionRepository auctionRepository;
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final PenaltyRepository penaltyRepository;
    private final BackupOfferRepository backupOfferRepository;
    private final BidRepository bidRepository;
    private final BidRestrictionPolicy bidRestrictionPolicy;
    private final Clock clock;
    private final NotificationRecorder notificationRecorder;

    public OrderExpirationService(
            AuctionRepository auctionRepository,
            OrderRepository orderRepository,
            UserRepository userRepository,
            PenaltyRepository penaltyRepository,
            BackupOfferRepository backupOfferRepository,
            BidRepository bidRepository,
            BidRestrictionPolicy bidRestrictionPolicy,
            Clock clock,
            NotificationRecorder notificationRecorder
    ) {
        this.auctionRepository = auctionRepository;
        this.orderRepository = orderRepository;
        this.userRepository = userRepository;
        this.penaltyRepository = penaltyRepository;
        this.backupOfferRepository = backupOfferRepository;
        this.bidRepository = bidRepository;
        this.bidRestrictionPolicy = bidRestrictionPolicy;
        this.clock = clock;
        this.notificationRecorder = notificationRecorder;
    }

    @Transactional
    public void expireIfDue(Long orderId) {
        Long auctionId = orderRepository.findAuctionIdById(orderId).orElse(null);
        if (auctionId == null) {
            return;
        }

        Auction auction = auctionRepository.findByIdForUpdate(auctionId).orElse(null);
        if (auction == null) {
            return;
        }

        Order order = orderRepository.findByIdForUpdate(orderId).orElse(null);
        if (order == null) {
            return;
        }

        LocalDateTime now = LocalDateTime.now(clock);
        if (order.getStatus() != OrderStatus.PAYMENT_PENDING || !order.getPaymentDeadline().isBefore(now)) {
            // 스케줄러가 non-locking으로 고른 후보가 이 락 사이에 이미 결제/포기됐거나, 아직
            // 기한 전이다 - 조용히 넘어간다(재실행 시 중복 처리 없음의 핵심 방어선).
            return;
        }

        order.expire();

        User buyer = order.getBuyer();
        // #75: 위 guard를 통과해 실제 PAYMENT_PENDING -> PAYMENT_EXPIRED 전이가 일어난 경우에만
        // 기록한다 - 재실행 시에는 guard가 먼저 걸려(status != PAYMENT_PENDING) 이 지점에 도달하지 않는다.
        notificationRecorder.record(buyer, NotificationType.PAYMENT_EXPIRED, auctionId, order.getId());

        if (!penaltyRepository.existsByAuction_IdAndUser_IdAndType(auctionId, buyer.getId(), PenaltyType.PAYMENT_EXPIRED)) {
            User lockedBuyer = userRepository.findByIdForUpdate(buyer.getId()).orElseThrow();
            penaltyRepository.save(Penalty.paymentExpired(lockedBuyer, auction));
            lockedBuyer.recordPaymentExpiredPenalty(bidRestrictionPolicy.restrictedUntil(now));
        }

        createNextBackupOfferIfCandidateExists(auction, buyer);
    }

    // 만료된 Order의 buyer가 지금 몇 등인지(원 낙찰자 rank1, 차순위 수락자 rank2/3)를 찾아 그
    // 다음 순위에게 제안한다 - BackupCandidateSelector가 rank2->rank3 체이닝을 이미 일반화해
    // 두었으므로(#56-3) 새 순위 정책을 만들지 않고 그대로 재사용한다. rank3까지 소진되면
    // next()가 empty를 반환해 자연히 체인이 끝난다(MAX_BACKUP_RANK=3).
    private void createNextBackupOfferIfCandidateExists(Auction auction, User expiredBuyer) {
        List<Bid> ranked = bidRepository.findLatestBidPerUserOrderedByRank(auction.getId());
        Integer buyerRank = BackupCandidateSelector.rankOf(ranked, expiredBuyer.getId()).orElse(null);
        if (buyerRank == null) {
            // 이론상 도달하지 않는다 - Order가 존재한다는 것 자체가 buyer가 입찰 기록을 가진
            // rank1/2/3 중 하나였다는 뜻이다. 방어적으로 조용히 넘어간다.
            return;
        }

        BackupCandidateSelector.next(ranked, buyerRank).ifPresent(candidateBid -> {
            User candidate = candidateBid.getUser();
            if (backupOfferRepository.findByAuctionIdAndCandidateId(auction.getId(), candidate.getId()).isPresent()) {
                // 방어적 중복 방지 - uk_backup_offer_auction_candidate가 최종 방어선이다
                // (AuctionForfeitService/BackupOfferCommandService와 동일한 패턴).
                return;
            }
            BackupOffer saved = backupOfferRepository.save(BackupOffer.create(auction, candidate, candidateBid.getAmount()));
            // #75: 신규 BackupOffer가 실제 저장된 경우에만 기록한다(네 번째 BackupOffer 생성 경로 - 위
            // PAYMENT_EXPIRED 기록과는 별개의 이벤트다).
            notificationRecorder.record(candidate, NotificationType.BACKUP_OFFER_CREATED, auction.getId(), saved.getId());
        });
    }
}
