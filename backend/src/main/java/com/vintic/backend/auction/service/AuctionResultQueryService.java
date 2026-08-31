package com.vintic.backend.auction.service;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.domain.AuctionResult;
import com.vintic.backend.auction.dto.AuctionResultResponse;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.backupoffer.domain.BackupOffer;
import com.vintic.backend.backupoffer.domain.BackupOfferStatus;
import com.vintic.backend.backupoffer.repository.BackupOfferRepository;
import com.vintic.backend.bid.domain.Bid;
import com.vintic.backend.bid.repository.BidRepository;
import com.vintic.backend.common.exception.AuctionNotFoundException;
import com.vintic.backend.common.util.ProductDisplayName;
import com.vintic.backend.common.util.TimePolicy;
import com.vintic.backend.order.domain.Order;
import com.vintic.backend.order.domain.OrderStatus;
import com.vintic.backend.order.repository.OrderRepository;
import com.vintic.backend.penalty.domain.PenaltyType;
import com.vintic.backend.penalty.repository.PenaltyRepository;
import com.vintic.backend.product.domain.Product;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

// FINAL contract §10. #56-0 확정: Result는 persisted entity가 아니라 Auction/Order/BackupOffer/
// Penalty 상태로부터 매 조회마다 계산한다. Order/BackupOffer/Penalty 생성은 각각
// AuctionSettlementService/AuctionForfeitService의 책임이고 이 서비스는 그 결과를 읽기만
// 한다(side-effect free) - 정산 전(ENDED인데 아직 settle()이 실행되지 않은) 경매를 조회하면
// 실제 낙찰자도 WON이 아니라 LOST로 보일 수 있다. 이는 "GET이 Order를 lazy 생성하지 않는다"는
// #56-0 결정의 예상된 결과이고, 실제 프로덕션에서는 lifecycle 스케줄러가 병합되면 /result가
// 조회되는 시점엔 이미 settlement가 끝나 있는 것이 전제다(#44/#45가 이미 같은 전제로 남겨둔
// DEFERRED UNTIL LIFECYCLE INTEGRATION 항목과 동일한 성격의 gap).
//
// NO_BIDS/WON/LOST/BACKUP_WAITING/FORFEITED/PAYMENT_EXPIRED 전부 판정 가능하다(PAYMENT_EXPIRED만
// 여전히 production에서 도달 불가 - Order를 그 상태로 전이시키는 scheduler가 #57). 판정 우선순위는
// #56-0이 확정한 순서(§Result 판정 예) 그대로다: NO_BIDS -> BACKUP_WAITING -> WON -> FORFEITED ->
// PAYMENT_EXPIRED -> LOST. #56-3(accept/decline)이 BackupOffer 상태를 실제로 바꾸기 시작하면서
// WON/backupEligible 계산이 "원 낙찰자"와 "차순위 수락자" 두 종류를 모두 정확히 구분해야 한다 -
// finalPrice/backupEligible 관련 주석 참고.
@Service
public class AuctionResultQueryService {

    private final AuctionRepository auctionRepository;
    private final BidRepository bidRepository;
    private final OrderRepository orderRepository;
    private final BackupOfferRepository backupOfferRepository;
    private final PenaltyRepository penaltyRepository;
    private final Clock clock;

    public AuctionResultQueryService(
            AuctionRepository auctionRepository,
            BidRepository bidRepository,
            OrderRepository orderRepository,
            BackupOfferRepository backupOfferRepository,
            PenaltyRepository penaltyRepository,
            Clock clock
    ) {
        this.auctionRepository = auctionRepository;
        this.bidRepository = bidRepository;
        this.orderRepository = orderRepository;
        this.backupOfferRepository = backupOfferRepository;
        this.penaltyRepository = penaltyRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AuctionResultResponse getResult(Long auctionId, Long userId) {
        Auction auction = auctionRepository.findByIdWithProductAndWinner(auctionId)
                .orElseThrow(() -> new AuctionNotFoundException("존재하지 않는 경매입니다. auctionId: " + auctionId));
        Product product = auction.getProduct();

        // #56-0 확정: rank/myLastBidAmount 둘 다 이 한 쿼리로 계산한다 - 사용자당 최신(=최고,
        // Bid 주석 참고) Bid 하나만 남긴 뒤 §0.12 FIRST-IN WINS로 정렬한 목록에서 내 순위를 찾는다.
        List<Bid> ranked = bidRepository.findLatestBidPerUserOrderedByRank(auctionId);
        Integer rank = null;
        Long myLastBidAmount = null;
        for (int i = 0; i < ranked.size(); i++) {
            Bid bid = ranked.get(i);
            if (bid.getUser().getId().equals(userId)) {
                rank = i + 1;
                myLastBidAmount = bid.getAmount();
                break;
            }
        }

        Optional<Order> order = orderRepository.findByAuctionIdAndBuyerId(auctionId, userId);
        Optional<BackupOffer> backupOffer = backupOfferRepository.findByAuctionIdAndCandidateId(auctionId, userId);
        boolean forfeited = penaltyRepository.existsByAuction_IdAndUser_IdAndType(auctionId, userId, PenaltyType.FORFEITED);
        AuctionResult result = determineResult(ranked.isEmpty(), order, backupOffer, forfeited);

        Long finalPrice = ranked.isEmpty() ? null : auction.getCurrentPrice();
        Long shippingFee = null;
        Long totalAmount = null;
        OffsetDateTime paymentDeadline = null;
        Long orderId = null;
        if (result == AuctionResult.WON) {
            Order won = order.orElseThrow(); // determineResult가 WON을 반환했다면 order는 반드시 present다.
            // 차순위 수락자(#56-3)는 order.purchasePrice가 auction.currentPrice(원 낙찰가)와 다르다
            // (§12: purchasePrice로 통일). finalPrice는 "이 사용자가 실제로 지불하는 금액"을
            // 의미해야 하므로 여기서 order 기준으로 덮어쓴다 - 원 낙찰자는 두 값이 항상 같아 결과가
            // 바뀌지 않는다.
            finalPrice = won.getPurchasePrice();
            shippingFee = won.getShippingFee();
            totalAmount = won.getTotalAmount();
            paymentDeadline = TimePolicy.toApiTime(won.getPaymentDeadline());
            orderId = won.getId();
        }

        // orderId는 WON일 때만, backupOfferId는 BACKUP_WAITING일 때만 값을 갖는다(§10).
        Long backupOfferId = result == AuctionResult.BACKUP_WAITING ? backupOffer.orElseThrow().getId() : null;

        // #56-0 확정: rank 2/3만 차순위 후보고, "아직 소진되지 않은 경우"만 true다. BACKUP_WAITING은
        // 정의상 WAITING offer가 있다는 뜻이라 항상 소진 전이다(true). LOST에서는 offer가 아예
        // 없어야("아직 forfeit이 이 사람 차례까지 오지 않음") true다 - offer가 있는데 LOST라면
        // determineResult()가 WAITING/PENDING/PAID 분기를 이미 앞에서 다 걸러냈다는 뜻이므로 그
        // offer는 DECLINED/EXPIRED로 소진된 상태다(true면 안 된다).
        boolean backupEligible;
        if (result == AuctionResult.BACKUP_WAITING) {
            backupEligible = true;
        } else if (result == AuctionResult.LOST) {
            backupEligible = rank != null && (rank == 2 || rank == 3) && backupOffer.isEmpty();
        } else {
            backupEligible = false;
        }

        return new AuctionResultResponse(
                auction.getId(),
                result,
                new AuctionResultResponse.Product(
                        product.getId(),
                        ProductDisplayName.name(product),
                        ProductDisplayName.subName(product),
                        product.getImageUrls().isEmpty() ? null : product.getImageUrls().get(0)
                ),
                rank,
                finalPrice,
                myLastBidAmount,
                shippingFee,
                totalAmount,
                paymentDeadline,
                TimePolicy.toApiTime(LocalDateTime.now(clock)),
                orderId,
                backupOfferId,
                backupEligible
        );
    }

    // #56-0이 확정한 우선순위 그대로다: NO_BIDS -> BACKUP_WAITING -> WON -> FORFEITED ->
    // PAYMENT_EXPIRED -> LOST. FORFEITED가 WON보다 뒤에 있어도 실제로는 겹치지 않는다 - forfeit이
    // Order를 CANCELED로 전이시키므로 WON 조건(PENDING/PAID)에 애초에 걸리지 않는다.
    private AuctionResult determineResult(
            boolean noBids, Optional<Order> order, Optional<BackupOffer> backupOffer, boolean forfeited
    ) {
        if (noBids) {
            return AuctionResult.NO_BIDS;
        }
        if (backupOffer.isPresent() && backupOffer.get().getStatus() == BackupOfferStatus.WAITING) {
            return AuctionResult.BACKUP_WAITING;
        }
        if (order.isPresent()) {
            OrderStatus status = order.get().getStatus();
            if (status == OrderStatus.PAYMENT_PENDING || status == OrderStatus.PAID) {
                return AuctionResult.WON;
            }
            if (status == OrderStatus.PAYMENT_EXPIRED) {
                return AuctionResult.PAYMENT_EXPIRED;
            }
            // CANCELED - 아래 forfeited 체크로 넘어간다(이 프로젝트에서 CANCELED의 유일한 원인).
        }
        if (forfeited) {
            return AuctionResult.FORFEITED;
        }
        return AuctionResult.LOST;
    }
}
