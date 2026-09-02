package com.vintic.backend.order.service;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.backupoffer.domain.BackupOffer;
import com.vintic.backend.backupoffer.repository.BackupOfferRepository;
import com.vintic.backend.bid.domain.Bid;
import com.vintic.backend.bid.domain.BidType;
import com.vintic.backend.bid.repository.BidRepository;
import com.vintic.backend.config.ClockConfig;
import com.vintic.backend.notification.domain.Notification;
import com.vintic.backend.notification.domain.NotificationType;
import com.vintic.backend.notification.repository.NotificationRepository;
import com.vintic.backend.notification.service.NotificationRecorder;
import com.vintic.backend.order.domain.Order;
import com.vintic.backend.order.domain.OrderStatus;
import com.vintic.backend.order.repository.OrderRepository;
import com.vintic.backend.penalty.domain.Penalty;
import com.vintic.backend.penalty.domain.PenaltyType;
import com.vintic.backend.penalty.repository.PenaltyRepository;
import com.vintic.backend.penalty.service.BidRestrictionPolicy;
import com.vintic.backend.product.domain.Product;
import com.vintic.backend.support.TestClockConfig;
import com.vintic.backend.user.domain.User;
import com.vintic.backend.user.repository.UserRepository;
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

// FINAL contract §12/§0.10, #57-2.
// #75: PAYMENT_EXPIRED Notification 연결 - NotificationRecorder는 이 서비스의 트랜잭션에 참여한다.
@DataJpaTest
@Import({OrderExpirationService.class, TestClockConfig.class, BidRestrictionPolicy.class, NotificationRecorder.class})
class OrderExpirationServiceTest {

    private static final LocalDateTime FIXED_NOW = LocalDateTime.ofInstant(TestClockConfig.FIXED_INSTANT, ClockConfig.APP_ZONE);

    @Autowired
    private OrderExpirationService orderExpirationService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private UserRepository userRepository;

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

    // rank1(winner)/rank2/rank3 - BackupOfferCommandServiceTest와 동일한 fixture 구성.
    private Auction persistEndedAuctionWithThreeBidders(User winner, User rank2, User rank3) {
        User seller = persistUser("seller-" + System.nanoTime() + "@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = Auction.schedule(
                product, 10000L, 5000L, LocalDateTime.now().minusHours(2), LocalDateTime.now().minusHours(1)
        );
        auction.start();
        entityManager.persist(auction);
        bidRepository.save(Bid.place(auction, rank3, 15000L, BidType.MANUAL));
        auction.placeManualBid(rank3, 15000L);
        bidRepository.save(Bid.place(auction, rank2, 20000L, BidType.MANUAL));
        auction.placeManualBid(rank2, 20000L);
        bidRepository.save(Bid.place(auction, winner, 25000L, BidType.MANUAL));
        auction.placeManualBid(winner, 25000L);
        auction.end();
        return auction;
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void deadline_이전_PAYMENT_PENDING_Order는_유지된다() {
        User winner = persistUser("winner@vintic.local");
        User rank2 = persistUser("rank2@vintic.local");
        User rank3 = persistUser("rank3@vintic.local");
        Auction auction = persistEndedAuctionWithThreeBidders(winner, rank2, rank3);
        Order order = orderRepository.save(Order.createForWinner(auction, winner, 25000L, 3000L, FIXED_NOW.plusHours(1)));
        flushAndClear();

        orderExpirationService.expireIfDue(order.getId());

        Order reloaded = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(penaltyRepository.count()).isZero();
        assertThat(backupOfferRepository.count()).isZero();
        // #75: 아직 due가 아니면 Notification도 기록되지 않는다.
        assertThat(notificationRepository.count()).isZero();
    }

    @Test
    void deadline_지난_PAYMENT_PENDING_Order는_PAYMENT_EXPIRED로_전이한다() {
        User winner = persistUser("winner@vintic.local");
        User rank2 = persistUser("rank2@vintic.local");
        User rank3 = persistUser("rank3@vintic.local");
        Auction auction = persistEndedAuctionWithThreeBidders(winner, rank2, rank3);
        Order order = orderRepository.save(Order.createForWinner(auction, winner, 25000L, 3000L, FIXED_NOW.minusMinutes(1)));
        flushAndClear();

        orderExpirationService.expireIfDue(order.getId());

        Order reloaded = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.PAYMENT_EXPIRED);

        // #75: 실제 전이가 일어난 이 경우에만 PAYMENT_EXPIRED Notification이 buyer에게 기록된다
        // (rank2에게 가는 BACKUP_OFFER_CREATED Notification과 합쳐 총 2건).
        assertThat(notificationRepository.count()).isEqualTo(2);
        Notification paymentExpired = notificationRepository.findAll().stream()
                .filter(n -> n.getType() == NotificationType.PAYMENT_EXPIRED)
                .findFirst().orElseThrow();
        assertThat(paymentExpired.getRecipient().getId()).isEqualTo(winner.getId());
        assertThat(paymentExpired.getAuctionId()).isEqualTo(auction.getId());
        assertThat(paymentExpired.getResourceId()).isEqualTo(order.getId());
        assertThat(paymentExpired.getBusinessEventKey()).isEqualTo("PAYMENT_EXPIRED:" + order.getId());
    }

    @Test
    void PAID_Order는_처리하지_않는다() {
        User winner = persistUser("winner@vintic.local");
        User rank2 = persistUser("rank2@vintic.local");
        User rank3 = persistUser("rank3@vintic.local");
        Auction auction = persistEndedAuctionWithThreeBidders(winner, rank2, rank3);
        Order order = Order.createForWinner(auction, winner, 25000L, 3000L, FIXED_NOW.minusMinutes(1));
        order.pay(FIXED_NOW.minusMinutes(30));
        orderRepository.save(order);
        flushAndClear();

        orderExpirationService.expireIfDue(order.getId());

        Order reloaded = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(penaltyRepository.count()).isZero();
        assertThat(backupOfferRepository.count()).isZero();
        assertThat(notificationRepository.count()).isZero();
    }

    @Test
    void CANCELED_Order는_처리하지_않는다() {
        User winner = persistUser("winner@vintic.local");
        User rank2 = persistUser("rank2@vintic.local");
        User rank3 = persistUser("rank3@vintic.local");
        Auction auction = persistEndedAuctionWithThreeBidders(winner, rank2, rank3);
        Order order = Order.createForWinner(auction, winner, 25000L, 3000L, FIXED_NOW.minusMinutes(1));
        order.cancel();
        orderRepository.save(order);
        flushAndClear();

        orderExpirationService.expireIfDue(order.getId());

        Order reloaded = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.CANCELED);
        assertThat(penaltyRepository.count()).isZero();
        assertThat(backupOfferRepository.count()).isZero();
        assertThat(notificationRepository.count()).isZero();
    }

    @Test
    void 만료_처리시_PAYMENT_EXPIRED_penalty가_정확히_1건_생성되고_noShowCount와_bidRestrictedUntil이_갱신된다() {
        User winner = persistUser("winner@vintic.local");
        User rank2 = persistUser("rank2@vintic.local");
        User rank3 = persistUser("rank3@vintic.local");
        Auction auction = persistEndedAuctionWithThreeBidders(winner, rank2, rank3);
        Order order = orderRepository.save(Order.createForWinner(auction, winner, 25000L, 3000L, FIXED_NOW.minusMinutes(1)));
        flushAndClear();

        orderExpirationService.expireIfDue(order.getId());

        assertThat(penaltyRepository.count()).isEqualTo(1);
        Penalty penalty = penaltyRepository.findAll().get(0);
        assertThat(penalty.getType()).isEqualTo(PenaltyType.PAYMENT_EXPIRED);
        assertThat(penalty.getUser().getId()).isEqualTo(winner.getId());
        assertThat(penalty.getAuction().getId()).isEqualTo(auction.getId());

        User reloadedWinner = userRepository.findById(winner.getId()).orElseThrow();
        assertThat(reloadedWinner.getNoshowCount()).isEqualTo(1);
        // 기본 정책 = 고정 7일(application.yml default). BidRestrictionPolicy 자체 공식은
        // BidRestrictionPolicyTest가 별도로 고정한다 - 여기서는 실제로 User에 반영되는지만 확인한다.
        assertThat(reloadedWinner.getBidRestrictedUntil()).isCloseTo(FIXED_NOW.plusDays(7), within(1, ChronoUnit.SECONDS));
    }

    @Test
    void 재실행해도_penalty와_BackupOffer가_중복_생성되지_않는다() {
        User winner = persistUser("winner@vintic.local");
        User rank2 = persistUser("rank2@vintic.local");
        User rank3 = persistUser("rank3@vintic.local");
        Auction auction = persistEndedAuctionWithThreeBidders(winner, rank2, rank3);
        Order order = orderRepository.save(Order.createForWinner(auction, winner, 25000L, 3000L, FIXED_NOW.minusMinutes(1)));
        flushAndClear();

        orderExpirationService.expireIfDue(order.getId());
        orderExpirationService.expireIfDue(order.getId());
        orderExpirationService.expireIfDue(order.getId());

        assertThat(penaltyRepository.count()).isEqualTo(1);
        assertThat(backupOfferRepository.count()).isEqualTo(1);
        User reloadedWinner = userRepository.findById(winner.getId()).orElseThrow();
        assertThat(reloadedWinner.getNoshowCount()).isEqualTo(1);
        // #75: 재실행해도 최초 만료가 만든 PAYMENT_EXPIRED(winner) + BACKUP_OFFER_CREATED(rank2)
        // 2건만 유지된다.
        assertThat(notificationRepository.count()).isEqualTo(2);
    }

    @Test
    void 낙찰자_Order가_만료되면_rank2에게_BackupOffer가_1건_생성된다() {
        User winner = persistUser("winner@vintic.local");
        User rank2 = persistUser("rank2@vintic.local");
        User rank3 = persistUser("rank3@vintic.local");
        Auction auction = persistEndedAuctionWithThreeBidders(winner, rank2, rank3);
        Order order = orderRepository.save(Order.createForWinner(auction, winner, 25000L, 3000L, FIXED_NOW.minusMinutes(1)));
        flushAndClear();

        orderExpirationService.expireIfDue(order.getId());

        assertThat(backupOfferRepository.count()).isEqualTo(1);
        BackupOffer offer = backupOfferRepository.findByAuctionIdAndCandidateId(auction.getId(), rank2.getId()).orElseThrow();
        assertThat(offer.getPurchasePrice()).isEqualTo(20000L);

        // #75: PAYMENT_EXPIRED(winner) + BACKUP_OFFER_CREATED(rank2) 총 2건.
        assertThat(notificationRepository.count()).isEqualTo(2);
        Notification backupOfferCreated = notificationRepository.findAll().stream()
                .filter(n -> n.getType() == NotificationType.BACKUP_OFFER_CREATED)
                .findFirst().orElseThrow();
        assertThat(backupOfferCreated.getRecipient().getId()).isEqualTo(rank2.getId());
        assertThat(backupOfferCreated.getResourceId()).isEqualTo(offer.getId());
    }

    @Test
    void rank2_수락_Order가_만료되면_rank3에게_BackupOffer가_생성된다() {
        User winner = persistUser("winner@vintic.local");
        User rank2 = persistUser("rank2@vintic.local");
        User rank3 = persistUser("rank3@vintic.local");
        Auction auction = persistEndedAuctionWithThreeBidders(winner, rank2, rank3);
        Order backupOrder = orderRepository.save(
                Order.createForBackupAccept(auction, rank2, 20000L, 3000L, FIXED_NOW.minusMinutes(1))
        );
        flushAndClear();

        orderExpirationService.expireIfDue(backupOrder.getId());

        Order reloaded = orderRepository.findById(backupOrder.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.PAYMENT_EXPIRED);
        assertThat(backupOfferRepository.count()).isEqualTo(1);
        BackupOffer offer = backupOfferRepository.findByAuctionIdAndCandidateId(auction.getId(), rank3.getId()).orElseThrow();
        assertThat(offer.getPurchasePrice()).isEqualTo(15000L);

        // #75: PAYMENT_EXPIRED(rank2) + BACKUP_OFFER_CREATED(rank3) 총 2건.
        assertThat(notificationRepository.count()).isEqualTo(2);
    }

    @Test
    void rank3_수락_Order가_만료되면_추가_BackupOffer가_생성되지_않는다() {
        User winner = persistUser("winner@vintic.local");
        User rank2 = persistUser("rank2@vintic.local");
        User rank3 = persistUser("rank3@vintic.local");
        Auction auction = persistEndedAuctionWithThreeBidders(winner, rank2, rank3);
        Order backupOrder = orderRepository.save(
                Order.createForBackupAccept(auction, rank3, 15000L, 3000L, FIXED_NOW.minusMinutes(1))
        );
        flushAndClear();

        orderExpirationService.expireIfDue(backupOrder.getId());

        Order reloaded = orderRepository.findById(backupOrder.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.PAYMENT_EXPIRED);
        assertThat(backupOfferRepository.count()).isZero();
        // #75: rank3(마지막 순위)는 BACKUP_OFFER_CREATED 없이 PAYMENT_EXPIRED 1건만 남는다.
        assertThat(notificationRepository.count()).isEqualTo(1);
        assertThat(notificationRepository.findAll().get(0).getType()).isEqualTo(NotificationType.PAYMENT_EXPIRED);
    }

    @Test
    void 존재하지_않는_Order를_처리해도_예외없이_아무일도_일어나지_않는다() {
        orderExpirationService.expireIfDue(9999L);

        assertThat(penaltyRepository.count()).isZero();
        assertThat(notificationRepository.count()).isZero();
    }
}
