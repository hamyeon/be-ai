package com.vintic.backend.auction.service;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.domain.AuctionResult;
import com.vintic.backend.auction.dto.AuctionResultResponse;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.bid.domain.Bid;
import com.vintic.backend.bid.repository.BidRepository;
import com.vintic.backend.common.exception.AuctionNotFoundException;
import com.vintic.backend.common.util.ProductDisplayName;
import com.vintic.backend.common.util.TimePolicy;
import com.vintic.backend.order.domain.Order;
import com.vintic.backend.order.domain.OrderStatus;
import com.vintic.backend.order.repository.OrderRepository;
import com.vintic.backend.product.domain.Product;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

// FINAL contract §10. #56-0 확정: Result는 persisted entity가 아니라 Auction/Order(+ 향후
// BackupOffer/UserPenalty) 상태로부터 매 조회마다 계산한다. Order 생성은 AuctionSettlementService의
// 책임이고 이 서비스는 그 결과를 읽기만 한다(side-effect free) - 정산 전(ENDED인데 아직
// settle()이 실행되지 않은) 경매를 조회하면 실제 낙찰자도 WON이 아니라 LOST로 보일 수 있다.
// 이는 "GET이 Order를 lazy 생성하지 않는다"는 #56-0 결정의 예상된 결과이고, 실제 프로덕션에서는
// lifecycle 스케줄러가 병합되면 /result가 조회되는 시점엔 이미 settlement가 끝나 있는 것이
// 전제다(#44/#45가 이미 같은 전제로 남겨둔 DEFERRED UNTIL LIFECYCLE INTEGRATION 항목과 동일한
// 성격의 gap).
//
// #56-1 범위: NO_BIDS/WON/LOST/PAYMENT_EXPIRED만 실제로 도달 가능하다. BACKUP_WAITING은
// BackupOffer, FORFEITED는 UserPenalty가 있어야 판정할 수 있는데 두 도메인 모두 이번 범위에
// 없다 - #56-2에서 이 메서드에 두 분기를 추가한다(그 전까지는 코드에 없다, 존재하지 않는
// 엔티티를 미리 참조하는 죽은 분기를 만들지 않았다).
@Service
public class AuctionResultQueryService {

    private final AuctionRepository auctionRepository;
    private final BidRepository bidRepository;
    private final OrderRepository orderRepository;
    private final Clock clock;

    public AuctionResultQueryService(
            AuctionRepository auctionRepository,
            BidRepository bidRepository,
            OrderRepository orderRepository,
            Clock clock
    ) {
        this.auctionRepository = auctionRepository;
        this.bidRepository = bidRepository;
        this.orderRepository = orderRepository;
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
        AuctionResult result = determineResult(ranked.isEmpty(), order);

        Long finalPrice = ranked.isEmpty() ? null : auction.getCurrentPrice();
        Long shippingFee = null;
        Long totalAmount = null;
        OffsetDateTime paymentDeadline = null;
        Long orderId = null;
        if (result == AuctionResult.WON) {
            Order won = order.orElseThrow(); // determineResult가 WON을 반환했다면 order는 반드시 present다.
            shippingFee = won.getShippingFee();
            totalAmount = won.getTotalAmount();
            paymentDeadline = TimePolicy.toApiTime(won.getPaymentDeadline());
            orderId = won.getId();
        }

        // #56-0 확정: rank 2/3만 차순위 후보다. "아직 소진되지 않았는지"는 BackupOffer가 있어야
        // 판정할 수 있는데(#56-2) 이번 범위엔 없으므로, LOST이고 rank가 2/3이면 항상 true다.
        boolean backupEligible = result == AuctionResult.LOST && rank != null && (rank == 2 || rank == 3);

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
                null, // backupOfferId: #56-1엔 BackupOffer 도메인이 없어 항상 null(#56-2에서 채움).
                backupEligible
        );
    }

    private AuctionResult determineResult(boolean noBids, Optional<Order> order) {
        if (noBids) {
            return AuctionResult.NO_BIDS;
        }
        if (order.isPresent()) {
            OrderStatus status = order.get().getStatus();
            if (status == OrderStatus.PAYMENT_PENDING || status == OrderStatus.PAID) {
                return AuctionResult.WON;
            }
            if (status == OrderStatus.PAYMENT_EXPIRED) {
                return AuctionResult.PAYMENT_EXPIRED;
            }
            // CANCELED(forfeit)는 #56-1에 도달 경로가 없다 - #56-2에서 FORFEITED penalty 존재 여부로
            // 갈라지게 된다. 그 전까지는 LOST로 떨어진다(아래 return과 동일 분기).
        }
        return AuctionResult.LOST;
    }
}
