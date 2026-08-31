package com.vintic.backend.auction.service;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.domain.AuctionResult;
import com.vintic.backend.auction.dto.AuctionResultResponse;
import com.vintic.backend.backupoffer.domain.BackupOffer;
import com.vintic.backend.backupoffer.repository.BackupOfferRepository;
import com.vintic.backend.bid.domain.Bid;
import com.vintic.backend.bid.domain.BidType;
import com.vintic.backend.bid.repository.BidRepository;
import com.vintic.backend.order.domain.Order;
import com.vintic.backend.order.repository.OrderRepository;
import com.vintic.backend.penalty.domain.Penalty;
import com.vintic.backend.penalty.repository.PenaltyRepository;
import com.vintic.backend.product.domain.Product;
import com.vintic.backend.support.TestClockConfig;
import com.vintic.backend.user.domain.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

// FINAL contract §10. NO_BIDS/WON/LOST/BACKUP_WAITING/FORFEITED 전부 실제로 도달 가능하다
// (PAYMENT_EXPIRED만 여전히 scheduler가 없어 production 경로로 도달 불가 - #57).
@DataJpaTest
@Import({AuctionResultQueryService.class, TestClockConfig.class})
class AuctionResultQueryServiceTest {

    @Autowired
    private AuctionResultQueryService auctionResultQueryService;

    @Autowired
    private BidRepository bidRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private BackupOfferRepository backupOfferRepository;

    @Autowired
    private PenaltyRepository penaltyRepository;

    @Autowired
    private EntityManager entityManager;

    private User persistUser(String email) {
        User user = User.register(email, email, null);
        entityManager.persist(user);
        return user;
    }

    private Product persistProduct(User seller) {
        Product product = new Product(
                seller,
                List.of("https://example.com/a.jpg"),
                "Nike", "Dunk Low", "Panda", 270, "B", "PARTIAL",
                300000, 350000, "285,000원 ~ 315,000원", 290000, "사유", "설명"
        );
        entityManager.persist(product);
        return product;
    }

    private Auction persistLiveAuction(Product product) {
        Auction auction = Auction.schedule(
                product, 10000L, 5000L, LocalDateTime.now().minusHours(2), LocalDateTime.now().minusHours(1)
        );
        auction.start();
        entityManager.persist(auction);
        return auction;
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void 입찰이_없으면_NO_BIDS를_반환한다() {
        User seller = persistUser("seller@vintic.local");
        User viewer = persistUser("viewer@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        auction.end();
        flushAndClear();

        AuctionResultResponse response = auctionResultQueryService.getResult(auction.getId(), viewer.getId());

        assertThat(response.result()).isEqualTo(AuctionResult.NO_BIDS);
        assertThat(response.rank()).isNull();
        assertThat(response.myLastBidAmount()).isNull();
        assertThat(response.finalPrice()).isNull();
        assertThat(response.orderId()).isNull();
        assertThat(response.backupEligible()).isFalse();
    }

    @Test
    void 낙찰자는_settle된_Order가_있으면_WON과_orderId를_반환한다() {
        User seller = persistUser("seller@vintic.local");
        User winner = persistUser("winner@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        auction.placeManualBid(winner, 30000L);
        bidRepository.save(Bid.place(auction, winner, 30000L, BidType.MANUAL));
        auction.end();
        flushAndClear();

        Order order = orderRepository.save(Order.createForWinner(
                auction, winner, auction.getCurrentPrice(), 3000L, auction.getEndAt().plusHours(24)
        ));
        flushAndClear();

        AuctionResultResponse response = auctionResultQueryService.getResult(auction.getId(), winner.getId());

        assertThat(response.result()).isEqualTo(AuctionResult.WON);
        assertThat(response.rank()).isEqualTo(1);
        assertThat(response.finalPrice()).isEqualTo(30000L);
        assertThat(response.myLastBidAmount()).isEqualTo(30000L);
        assertThat(response.orderId()).isEqualTo(order.getId());
        assertThat(response.shippingFee()).isEqualTo(3000L);
        assertThat(response.totalAmount()).isEqualTo(33000L);
        // paymentDeadline은 /result와 /orders/{id}에서 항상 동일해야 한다(§0.10) - Order에 저장된
        // 값(= endsAt + 24h)을 그대로 내려주는지 확인한다.
        assertThat(response.paymentDeadline().toLocalDateTime())
                .isCloseTo(auction.getEndAt().plusHours(24), within(1, ChronoUnit.SECONDS));
        assertThat(response.backupEligible()).isFalse();
    }

    @Test
    void 낙찰자여도_settle되지_않은_경매는_Order가_없어_WON이_아니다() {
        // #56-0 확정: GET /result는 side-effect free라 Order를 대신 만들어주지 않는다 -
        // settlement(#AuctionSettlementService)가 아직 실행되지 않은 ENDED 경매는 실제 낙찰자를
        // 조회해도 WON으로 보이지 않는다(알려진 lifecycle-integration gap, 완료 보고 참고).
        User seller = persistUser("seller@vintic.local");
        User winner = persistUser("winner@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        auction.placeManualBid(winner, 30000L);
        bidRepository.save(Bid.place(auction, winner, 30000L, BidType.MANUAL));
        auction.end();
        flushAndClear();

        AuctionResultResponse response = auctionResultQueryService.getResult(auction.getId(), winner.getId());

        assertThat(response.result()).isNotEqualTo(AuctionResult.WON);
        assertThat(response.orderId()).isNull();
    }

    @Test
    void 패자는_rank와_myLastBidAmount를_반환하고_rank_2는_backupEligible이_true다() {
        User seller = persistUser("seller@vintic.local");
        User winner = persistUser("winner@vintic.local");
        User loser = persistUser("loser@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);

        bidRepository.save(Bid.place(auction, loser, 20000L, BidType.MANUAL));
        auction.placeManualBid(loser, 20000L);
        bidRepository.save(Bid.place(auction, winner, 30000L, BidType.MANUAL));
        auction.placeManualBid(winner, 30000L);
        auction.end();
        flushAndClear();

        AuctionResultResponse response = auctionResultQueryService.getResult(auction.getId(), loser.getId());

        assertThat(response.result()).isEqualTo(AuctionResult.LOST);
        assertThat(response.rank()).isEqualTo(2);
        assertThat(response.myLastBidAmount()).isEqualTo(20000L);
        assertThat(response.finalPrice()).isEqualTo(30000L);
        assertThat(response.orderId()).isNull();
        assertThat(response.backupEligible()).isTrue();
    }

    @Test
    void 참여하지_않은_사용자는_rank가_null이고_backupEligible은_false다() {
        User seller = persistUser("seller@vintic.local");
        User winner = persistUser("winner@vintic.local");
        User bystander = persistUser("bystander@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        bidRepository.save(Bid.place(auction, winner, 30000L, BidType.MANUAL));
        auction.placeManualBid(winner, 30000L);
        auction.end();
        flushAndClear();

        AuctionResultResponse response = auctionResultQueryService.getResult(auction.getId(), bystander.getId());

        assertThat(response.result()).isEqualTo(AuctionResult.LOST);
        assertThat(response.rank()).isNull();
        assertThat(response.myLastBidAmount()).isNull();
        assertThat(response.backupEligible()).isFalse();
    }

    @Test
    void 동일_금액이면_먼저_등록된_입찰이_더_높은_순위를_가진다() {
        // §0.12 FIRST-IN WINS - rank 계산에 새 tie-break 규칙을 만들지 않고 그대로 재사용한다.
        User seller = persistUser("seller@vintic.local");
        User first = persistUser("first@vintic.local");
        User second = persistUser("second@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        // Auction.placeManualBid()의 currentPrice monotonic 제약을 우회해 동일 금액 tie를
        // 인위적으로 만든다 - 이 테스트는 순수하게 rank 산정 쿼리의 tie-break만 검증한다.
        bidRepository.save(Bid.place(auction, first, 20000L, BidType.MANUAL));
        bidRepository.save(Bid.place(auction, second, 20000L, BidType.MANUAL));
        auction.end();
        flushAndClear();

        AuctionResultResponse firstResponse = auctionResultQueryService.getResult(auction.getId(), first.getId());
        AuctionResultResponse secondResponse = auctionResultQueryService.getResult(auction.getId(), second.getId());

        assertThat(firstResponse.rank()).isEqualTo(1);
        assertThat(secondResponse.rank()).isEqualTo(2);
    }

    @Test
    void FORFEITED_penalty가_있으면_Order가_CANCELED여도_FORFEITED를_반환한다() {
        User seller = persistUser("seller@vintic.local");
        User winner = persistUser("winner@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        bidRepository.save(Bid.place(auction, winner, 30000L, BidType.MANUAL));
        auction.placeManualBid(winner, 30000L);
        auction.end();
        Order order = orderRepository.save(Order.createForWinner(
                auction, winner, auction.getCurrentPrice(), 3000L, auction.getEndAt().plusHours(24)
        ));
        order.cancel();
        penaltyRepository.save(Penalty.forfeited(winner, auction));
        flushAndClear();

        AuctionResultResponse response = auctionResultQueryService.getResult(auction.getId(), winner.getId());

        assertThat(response.result()).isEqualTo(AuctionResult.FORFEITED);
        assertThat(response.orderId()).isNull();
        assertThat(response.backupEligible()).isFalse();
    }

    @Test
    void WAITING_BackupOffer가_있으면_BACKUP_WAITING과_backupOfferId를_반환한다() {
        User seller = persistUser("seller@vintic.local");
        User winner = persistUser("winner@vintic.local");
        User candidate = persistUser("candidate@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        bidRepository.save(Bid.place(auction, candidate, 20000L, BidType.MANUAL));
        auction.placeManualBid(candidate, 20000L);
        bidRepository.save(Bid.place(auction, winner, 30000L, BidType.MANUAL));
        auction.placeManualBid(winner, 30000L);
        auction.end();
        BackupOffer offer = backupOfferRepository.save(BackupOffer.create(auction, candidate, 20000L));
        flushAndClear();

        AuctionResultResponse response = auctionResultQueryService.getResult(auction.getId(), candidate.getId());

        assertThat(response.result()).isEqualTo(AuctionResult.BACKUP_WAITING);
        assertThat(response.backupOfferId()).isEqualTo(offer.getId());
        assertThat(response.rank()).isEqualTo(2);
        assertThat(response.backupEligible()).isTrue();
    }

    @Test
    void 차순위_수락자의_WON_finalPrice는_원_낙찰가가_아니라_자신의_구매금액이다() {
        // #56-3 lifecycle consistency 회귀: 차순위 수락자의 Order.purchasePrice(20000)는
        // 원 경매 낙찰가(auction.currentPrice=30000)와 다르다 - finalPrice가 auction.currentPrice를
        // 그대로 반환하면 이 사용자가 실제로 지불하는 금액과 화면에 모순이 생긴다.
        User seller = persistUser("seller@vintic.local");
        User winner = persistUser("winner@vintic.local");
        User candidate = persistUser("candidate@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        bidRepository.save(Bid.place(auction, candidate, 20000L, BidType.MANUAL));
        auction.placeManualBid(candidate, 20000L);
        bidRepository.save(Bid.place(auction, winner, 30000L, BidType.MANUAL));
        auction.placeManualBid(winner, 30000L);
        auction.end();
        orderRepository.save(Order.createForBackupAccept(
                auction, candidate, 20000L, 3000L, LocalDateTime.now().plusHours(24)
        ));
        flushAndClear();

        AuctionResultResponse response = auctionResultQueryService.getResult(auction.getId(), candidate.getId());

        assertThat(response.result()).isEqualTo(AuctionResult.WON);
        assertThat(response.finalPrice()).isEqualTo(20000L);
        assertThat(response.totalAmount()).isEqualTo(23000L);
    }

    @Test
    void 이미_DECLINED된_후보는_LOST여도_backupEligible이_false다() {
        // #56-3 lifecycle consistency 회귀: rank 2/3이라도 이미 소진된(DECLINED) 후보는 더 이상
        // 차순위 후보가 아니다(#56-0 §4 "아직 소진되지 않은 경우"만 true).
        User seller = persistUser("seller@vintic.local");
        User winner = persistUser("winner@vintic.local");
        User candidate = persistUser("candidate@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        bidRepository.save(Bid.place(auction, candidate, 20000L, BidType.MANUAL));
        auction.placeManualBid(candidate, 20000L);
        bidRepository.save(Bid.place(auction, winner, 30000L, BidType.MANUAL));
        auction.placeManualBid(winner, 30000L);
        auction.end();
        BackupOffer offer = backupOfferRepository.save(BackupOffer.create(auction, candidate, 20000L));
        offer.decline();
        flushAndClear();

        AuctionResultResponse response = auctionResultQueryService.getResult(auction.getId(), candidate.getId());

        assertThat(response.result()).isEqualTo(AuctionResult.LOST);
        assertThat(response.rank()).isEqualTo(2);
        assertThat(response.backupEligible()).isFalse();
    }
}
