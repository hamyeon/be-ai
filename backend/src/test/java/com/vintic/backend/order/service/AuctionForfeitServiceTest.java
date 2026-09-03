package com.vintic.backend.order.service;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.domain.AuctionResult;
import com.vintic.backend.auction.dto.AuctionForfeitResponse;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.backupoffer.domain.BackupOffer;
import com.vintic.backend.backupoffer.repository.BackupOfferRepository;
import com.vintic.backend.bid.domain.Bid;
import com.vintic.backend.bid.domain.BidType;
import com.vintic.backend.bid.repository.BidRepository;
import com.vintic.backend.common.exception.AlreadyPaidException;
import com.vintic.backend.common.exception.NotAwardeeException;
import com.vintic.backend.common.exception.PaymentExpiredException;
import com.vintic.backend.notification.domain.Notification;
import com.vintic.backend.notification.domain.NotificationType;
import com.vintic.backend.notification.repository.NotificationRepository;
import com.vintic.backend.notification.service.NotificationRecorder;
import com.vintic.backend.order.domain.Order;
import com.vintic.backend.order.domain.OrderStatus;
import com.vintic.backend.order.repository.OrderRepository;
import com.vintic.backend.penalty.domain.PenaltyType;
import com.vintic.backend.penalty.repository.PenaltyRepository;
import com.vintic.backend.product.domain.Product;
import com.vintic.backend.support.TestClockConfig;
import com.vintic.backend.user.domain.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

// FINAL contract §11.
// #75: BACKUP_OFFER_CREATED Notification 연결 - NotificationRecorder는 이 서비스의 트랜잭션에 참여한다.
@DataJpaTest
@Import({AuctionForfeitService.class, TestClockConfig.class, NotificationRecorder.class})
class AuctionForfeitServiceTest {

    @Autowired
    private AuctionForfeitService auctionForfeitService;

    @Autowired
    private AuctionRepository auctionRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PenaltyRepository penaltyRepository;

    @Autowired
    private BackupOfferRepository backupOfferRepository;

    @Autowired
    private BidRepository bidRepository;

    @Autowired
    private NotificationRepository notificationRepository;

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

    private Auction persistEndedAuction(Product product) {
        Auction auction = Auction.schedule(
                product, 10000L, 5000L, LocalDateTime.now().minusHours(2), LocalDateTime.now().minusHours(1)
        );
        auction.start();
        entityManager.persist(auction);
        return auction;
    }

    private Order persistPendingOrder(Auction auction, User winner, Long amount) {
        Order order = Order.createForWinner(auction, winner, amount, 3000L, auction.getEndAt().plusHours(24));
        entityManager.persist(order);
        return order;
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void 낙찰자가_아닌_사용자가_forfeit하면_NOT_AWARDEE_예외가_발생한다() {
        User seller = persistUser("seller@vintic.local");
        User winner = persistUser("winner@vintic.local");
        User bystander = persistUser("bystander@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistEndedAuction(product);
        bidRepository.save(Bid.place(auction, winner, 30000L, BidType.MANUAL));
        auction.placeManualBid(winner, 30000L);
        persistPendingOrder(auction, winner, 30000L);
        flushAndClear();

        assertThatThrownBy(() -> auctionForfeitService.forfeit(auction.getId(), bystander.getId()))
                .isInstanceOf(NotAwardeeException.class);
        assertThat(penaltyRepository.count()).isZero();
        assertThat(backupOfferRepository.count()).isZero();
    }

    @Test
    void PAID_주문에_forfeit하면_ALREADY_PAID_예외가_발생한다() {
        User seller = persistUser("seller@vintic.local");
        User winner = persistUser("winner@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistEndedAuction(product);
        bidRepository.save(Bid.place(auction, winner, 30000L, BidType.MANUAL));
        auction.placeManualBid(winner, 30000L);
        Order order = persistPendingOrder(auction, winner, 30000L);
        // pay endpoint는 #56-2 범위가 아니라 PAID 전이 도메인 메서드가 아직 없다 - AuctionTest의
        // bidRestrictedUntil 강제 설정과 동일한 방식(ReflectionTestUtils)으로 상태만 만든다.
        ReflectionTestUtils.setField(order, "status", OrderStatus.PAID);
        entityManager.merge(order);
        flushAndClear();

        assertThatThrownBy(() -> auctionForfeitService.forfeit(auction.getId(), winner.getId()))
                .isInstanceOf(AlreadyPaidException.class);
    }

    @Test
    void PAYMENT_EXPIRED_주문에_forfeit하면_PAYMENT_EXPIRED_예외가_발생한다() {
        User seller = persistUser("seller@vintic.local");
        User winner = persistUser("winner@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistEndedAuction(product);
        bidRepository.save(Bid.place(auction, winner, 30000L, BidType.MANUAL));
        auction.placeManualBid(winner, 30000L);
        Order order = persistPendingOrder(auction, winner, 30000L);
        ReflectionTestUtils.setField(order, "status", OrderStatus.PAYMENT_EXPIRED);
        entityManager.merge(order);
        flushAndClear();

        assertThatThrownBy(() -> auctionForfeitService.forfeit(auction.getId(), winner.getId()))
                .isInstanceOf(PaymentExpiredException.class);
    }

    @Test
    void 정상_forfeit은_Order를_CANCELED로_전이하고_penalty_1건과_BackupOffer_1건을_만든다() {
        User seller = persistUser("seller@vintic.local");
        User winner = persistUser("winner@vintic.local");
        User loser = persistUser("loser@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistEndedAuction(product);
        bidRepository.save(Bid.place(auction, loser, 20000L, BidType.MANUAL));
        auction.placeManualBid(loser, 20000L);
        bidRepository.save(Bid.place(auction, winner, 30000L, BidType.MANUAL));
        auction.placeManualBid(winner, 30000L);
        Order order = persistPendingOrder(auction, winner, 30000L);
        flushAndClear();

        AuctionForfeitResponse response = auctionForfeitService.forfeit(auction.getId(), winner.getId());

        assertThat(response.result()).isEqualTo(AuctionResult.FORFEITED);

        Order reloadedOrder = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(reloadedOrder.getStatus()).isEqualTo(OrderStatus.CANCELED);

        assertThat(penaltyRepository.count()).isEqualTo(1);
        assertThat(penaltyRepository.existsByAuction_IdAndUser_IdAndType(auction.getId(), winner.getId(), PenaltyType.FORFEITED))
                .isTrue();

        assertThat(backupOfferRepository.count()).isEqualTo(1);
        BackupOffer offer = backupOfferRepository.findByAuctionIdAndCandidateId(auction.getId(), loser.getId())
                .orElseThrow();
        // purchasePrice = 차순위 후보(rank 2)의 마지막 유효 입찰가.
        assertThat(offer.getPurchasePrice()).isEqualTo(20000L);
        // deadline = createdAt + 24h(§0.10).
        assertThat(offer.getDeadline()).isCloseTo(offer.getCreatedAt().plusHours(24), within(1, ChronoUnit.SECONDS));

        // #75: rank2 BackupOffer 생성과 함께 BACKUP_OFFER_CREATED Notification이 정확히 1건 기록된다.
        assertThat(notificationRepository.count()).isEqualTo(1);
        Notification notification = notificationRepository.findAll().get(0);
        assertThat(notification.getType()).isEqualTo(NotificationType.BACKUP_OFFER_CREATED);
        assertThat(notification.getRecipient().getId()).isEqualTo(loser.getId());
        assertThat(notification.getAuctionId()).isEqualTo(auction.getId());
        assertThat(notification.getResourceId()).isEqualTo(offer.getId());
        assertThat(notification.getBusinessEventKey()).isEqualTo("BACKUP_OFFER_CREATED:" + offer.getId());
    }

    @Test
    void 차순위_후보가_없으면_forfeit은_성공하되_BackupOffer를_만들지_않는다() {
        User seller = persistUser("seller@vintic.local");
        User winner = persistUser("winner@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistEndedAuction(product);
        bidRepository.save(Bid.place(auction, winner, 30000L, BidType.MANUAL));
        auction.placeManualBid(winner, 30000L);
        persistPendingOrder(auction, winner, 30000L);
        flushAndClear();

        AuctionForfeitResponse response = auctionForfeitService.forfeit(auction.getId(), winner.getId());

        assertThat(response.result()).isEqualTo(AuctionResult.FORFEITED);
        assertThat(backupOfferRepository.count()).isZero();
        assertThat(penaltyRepository.count()).isEqualTo(1);
        // #75: 차순위 후보가 없어 BackupOffer 자체가 생성되지 않으므로 Notification도 없다.
        assertThat(notificationRepository.count()).isZero();
    }

    @Test
    void 이미_CANCELED된_주문에_다시_forfeit해도_penalty와_BackupOffer가_추가_생성되지_않는다() {
        User seller = persistUser("seller@vintic.local");
        User winner = persistUser("winner@vintic.local");
        User loser = persistUser("loser@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistEndedAuction(product);
        bidRepository.save(Bid.place(auction, loser, 20000L, BidType.MANUAL));
        auction.placeManualBid(loser, 20000L);
        bidRepository.save(Bid.place(auction, winner, 30000L, BidType.MANUAL));
        auction.placeManualBid(winner, 30000L);
        persistPendingOrder(auction, winner, 30000L);
        flushAndClear();

        AuctionForfeitResponse first = auctionForfeitService.forfeit(auction.getId(), winner.getId());
        AuctionForfeitResponse second = auctionForfeitService.forfeit(auction.getId(), winner.getId());

        assertThat(first.result()).isEqualTo(AuctionResult.FORFEITED);
        assertThat(second.result()).isEqualTo(AuctionResult.FORFEITED);
        assertThat(penaltyRepository.count()).isEqualTo(1);
        assertThat(backupOfferRepository.count()).isEqualTo(1);
        // #75: 재실행(이미 CANCELED된 주문에 다시 forfeit)은 idempotent short-circuit으로 BackupOffer
        // 생성 로직 자체를 다시 타지 않으므로 Notification도 여전히 1건이다.
        assertThat(notificationRepository.count()).isEqualTo(1);
    }
}
