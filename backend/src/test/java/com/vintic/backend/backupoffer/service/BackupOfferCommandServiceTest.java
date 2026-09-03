package com.vintic.backend.backupoffer.service;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.backupoffer.domain.BackupOffer;
import com.vintic.backend.backupoffer.domain.BackupOfferStatus;
import com.vintic.backend.backupoffer.dto.BackupOfferAcceptResponse;
import com.vintic.backend.backupoffer.dto.BackupOfferDeclineResponse;
import com.vintic.backend.backupoffer.repository.BackupOfferRepository;
import com.vintic.backend.bid.domain.Bid;
import com.vintic.backend.bid.domain.BidType;
import com.vintic.backend.bid.repository.BidRepository;
import com.vintic.backend.common.exception.BackupOfferAccessDeniedException;
import com.vintic.backend.common.exception.BackupOfferAlreadyResolvedException;
import com.vintic.backend.common.exception.BackupOfferExpiredException;
import com.vintic.backend.common.exception.BackupOfferNotFoundException;
import com.vintic.backend.notification.domain.Notification;
import com.vintic.backend.notification.domain.NotificationType;
import com.vintic.backend.notification.repository.NotificationRepository;
import com.vintic.backend.notification.service.NotificationRecorder;
import com.vintic.backend.order.domain.Order;
import com.vintic.backend.order.repository.OrderRepository;
import com.vintic.backend.config.ClockConfig;
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

// FINAL contract §16-17.
// #75: BACKUP_OFFER_CREATED Notification 연결(decline 경로) - NotificationRecorder는 이 서비스의
// 트랜잭션에 참여한다.
@DataJpaTest
@Import({BackupOfferCommandService.class, TestClockConfig.class, NotificationRecorder.class})
class BackupOfferCommandServiceTest {

    // TestClockConfig가 주입하는 고정 시각 - BackupOfferCommandService의 LocalDateTime.now(clock)은
    // 항상 이 값을 반환한다. 실제 벽시계(LocalDateTime.now())와 비교하면 안 된다.
    private static final LocalDateTime FIXED_NOW = LocalDateTime.ofInstant(TestClockConfig.FIXED_INSTANT, ClockConfig.APP_ZONE);

    @Autowired
    private BackupOfferCommandService backupOfferCommandService;

    @Autowired
    private BackupOfferRepository backupOfferRepository;

    @Autowired
    private OrderRepository orderRepository;

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

    // rank1(winner)/rank2/rank3 세 명의 입찰자를 만든다 - decline의 "다음 순위" 체이닝을
    // 검증하려면 최소 3명이 필요하다.
    private Auction persistEndedAuctionWithThreeBidders(User winner, User rank2, User rank3) {
        User seller = persistUser("seller-" + System.nanoTime() + "@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = Auction.schedule(
                product, 10000L, 5000L, LocalDateTime.now().minusHours(2), LocalDateTime.now().minusHours(1)
        );
        auction.start();
        entityManager.persist(auction);
        // startPrice=10000, bidIncrement=5000 -> minNextBidAmount=15000. 세 입찰 모두
        // minNextBidAmount 이상 + 5000의 배수여야 한다(§9 alignment).
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
    void WAITING_제안을_수락하면_ACCEPTED로_전이하고_Order를_1건_생성한다() {
        User winner = persistUser("winner@vintic.local");
        User rank2 = persistUser("rank2@vintic.local");
        User rank3 = persistUser("rank3@vintic.local");
        Auction auction = persistEndedAuctionWithThreeBidders(winner, rank2, rank3);
        BackupOffer offer = backupOfferRepository.save(BackupOffer.create(auction, rank2, 20000L));
        flushAndClear();

        BackupOfferAcceptResponse response = backupOfferCommandService.accept(offer.getId(), rank2.getId());

        assertThat(response.status()).isEqualTo(BackupOfferStatus.ACCEPTED);
        assertThat(response.orderId()).isNotNull();
        assertThat(response.totalAmount()).isEqualTo(23000L); // purchasePrice(20000) + shippingFee(3000)

        BackupOffer reloaded = backupOfferRepository.findById(offer.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(BackupOfferStatus.ACCEPTED);

        assertThat(orderRepository.count()).isEqualTo(1);
        Order order = orderRepository.findByAuctionIdAndBuyerId(auction.getId(), rank2.getId()).orElseThrow();
        assertThat(order.getPurchasePrice()).isEqualTo(20000L); // = offer.purchasePrice, auction.finalPrice(30000)가 아니다.
        // paymentDeadline = 수락 시각(주입된 Clock 기준) + 24h(§0.10) - createdAt/deadline(제안
        // 자체의 필드)과 무관하다.
        assertThat(order.getPaymentDeadline()).isCloseTo(FIXED_NOW.plusHours(24), within(5, ChronoUnit.SECONDS));
    }

    @Test
    void 이미_ACCEPTED된_제안을_다시_수락하면_예외가_발생한다() {
        User winner = persistUser("winner@vintic.local");
        User rank2 = persistUser("rank2@vintic.local");
        User rank3 = persistUser("rank3@vintic.local");
        Auction auction = persistEndedAuctionWithThreeBidders(winner, rank2, rank3);
        BackupOffer offer = backupOfferRepository.save(BackupOffer.create(auction, rank2, 20000L));
        flushAndClear();

        backupOfferCommandService.accept(offer.getId(), rank2.getId());

        assertThatThrownBy(() -> backupOfferCommandService.accept(offer.getId(), rank2.getId()))
                .isInstanceOf(BackupOfferAlreadyResolvedException.class);
        assertThat(orderRepository.count()).isEqualTo(1);
    }

    @Test
    void 기한이_지난_제안을_수락하면_예외가_발생한다() {
        User winner = persistUser("winner@vintic.local");
        User rank2 = persistUser("rank2@vintic.local");
        User rank3 = persistUser("rank3@vintic.local");
        Auction auction = persistEndedAuctionWithThreeBidders(winner, rank2, rank3);
        BackupOffer offer = backupOfferRepository.save(BackupOffer.create(auction, rank2, 20000L));
        // deadline을 주입된 Clock 기준 과거로 강제 설정 - scheduler 없이도(#57 이전) accept가
        // lazy 만료 판정을 하는지 확인한다(AuctionTest의 bidRestrictedUntil 강제 설정과 동일한 방식).
        ReflectionTestUtils.setField(offer, "deadline", FIXED_NOW.minusMinutes(1));
        entityManager.merge(offer);
        flushAndClear();

        assertThatThrownBy(() -> backupOfferCommandService.accept(offer.getId(), rank2.getId()))
                .isInstanceOf(BackupOfferExpiredException.class);
        assertThat(orderRepository.count()).isZero();
    }

    @Test
    void 존재하지_않는_제안을_수락하면_예외가_발생한다() {
        assertThatThrownBy(() -> backupOfferCommandService.accept(9999L, 1L))
                .isInstanceOf(BackupOfferNotFoundException.class);
    }

    @Test
    void candidate가_아닌_사용자의_수락_시도는_403_예외가_발생하고_상태가_바뀌지_않는다() {
        User winner = persistUser("winner2@vintic.local");
        User rank2 = persistUser("rank2b@vintic.local");
        User rank3 = persistUser("rank3b@vintic.local");
        User stranger = persistUser("stranger@vintic.local");
        Auction auction = persistEndedAuctionWithThreeBidders(winner, rank2, rank3);
        BackupOffer offer = backupOfferRepository.save(BackupOffer.create(auction, rank2, 20000L));
        flushAndClear();

        assertThatThrownBy(() -> backupOfferCommandService.accept(offer.getId(), stranger.getId()))
                .isInstanceOf(BackupOfferAccessDeniedException.class);
        assertThat(orderRepository.count()).isZero();
        BackupOffer reloaded = backupOfferRepository.findById(offer.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(BackupOfferStatus.WAITING);
    }

    @Test
    void candidate가_아닌_사용자의_거절_시도는_403_예외가_발생하고_다음_제안이_생성되지_않는다() {
        User winner = persistUser("winner3@vintic.local");
        User rank2 = persistUser("rank2c@vintic.local");
        User rank3 = persistUser("rank3c@vintic.local");
        User stranger = persistUser("stranger2@vintic.local");
        Auction auction = persistEndedAuctionWithThreeBidders(winner, rank2, rank3);
        BackupOffer offer = backupOfferRepository.save(BackupOffer.create(auction, rank2, 20000L));
        flushAndClear();

        assertThatThrownBy(() -> backupOfferCommandService.decline(offer.getId(), stranger.getId()))
                .isInstanceOf(BackupOfferAccessDeniedException.class);
        assertThat(backupOfferRepository.count()).isEqualTo(1);
        BackupOffer reloaded = backupOfferRepository.findById(offer.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(BackupOfferStatus.WAITING);
    }

    @Test
    void WAITING_제안을_거절하면_DECLINED로_전이하고_다음_순위에게_새_제안을_1건_생성한다() {
        User winner = persistUser("winner@vintic.local");
        User rank2 = persistUser("rank2@vintic.local");
        User rank3 = persistUser("rank3@vintic.local");
        Auction auction = persistEndedAuctionWithThreeBidders(winner, rank2, rank3);
        BackupOffer offer = backupOfferRepository.save(BackupOffer.create(auction, rank2, 20000L));
        flushAndClear();

        BackupOfferDeclineResponse response = backupOfferCommandService.decline(offer.getId(), rank2.getId());

        assertThat(response.status()).isEqualTo(BackupOfferStatus.DECLINED);
        BackupOffer reloadedOffer = backupOfferRepository.findById(offer.getId()).orElseThrow();
        assertThat(reloadedOffer.getStatus()).isEqualTo(BackupOfferStatus.DECLINED);

        assertThat(backupOfferRepository.count()).isEqualTo(2);
        BackupOffer nextOffer = backupOfferRepository.findByAuctionIdAndCandidateId(auction.getId(), rank3.getId())
                .orElseThrow();
        assertThat(nextOffer.getStatus()).isEqualTo(BackupOfferStatus.WAITING);
        assertThat(nextOffer.getPurchasePrice()).isEqualTo(15000L);

        // #75: rank3에게 새로 생성된 BackupOffer에 대해 BACKUP_OFFER_CREATED Notification이 정확히 1건이다.
        assertThat(notificationRepository.count()).isEqualTo(1);
        Notification notification = notificationRepository.findAll().get(0);
        assertThat(notification.getType()).isEqualTo(NotificationType.BACKUP_OFFER_CREATED);
        assertThat(notification.getRecipient().getId()).isEqualTo(rank3.getId());
        assertThat(notification.getResourceId()).isEqualTo(nextOffer.getId());
        assertThat(notification.getBusinessEventKey()).isEqualTo("BACKUP_OFFER_CREATED:" + nextOffer.getId());
    }

    @Test
    void rank_3의_제안을_거절하면_추가_제안이_생성되지_않는다() {
        User winner = persistUser("winner@vintic.local");
        User rank2 = persistUser("rank2@vintic.local");
        User rank3 = persistUser("rank3@vintic.local");
        Auction auction = persistEndedAuctionWithThreeBidders(winner, rank2, rank3);
        BackupOffer offer = backupOfferRepository.save(BackupOffer.create(auction, rank3, 15000L));
        flushAndClear();

        backupOfferCommandService.decline(offer.getId(), rank3.getId());

        assertThat(backupOfferRepository.count()).isEqualTo(1);
        // #75: rank3(마지막 순위) 거절은 다음 BackupOffer를 만들지 않으므로 Notification도 없다.
        assertThat(notificationRepository.count()).isZero();
    }

    @Test
    void 이미_DECLINED된_제안을_다시_거절하면_예외가_발생하고_추가_제안도_생성되지_않는다() {
        User winner = persistUser("winner@vintic.local");
        User rank2 = persistUser("rank2@vintic.local");
        User rank3 = persistUser("rank3@vintic.local");
        Auction auction = persistEndedAuctionWithThreeBidders(winner, rank2, rank3);
        BackupOffer offer = backupOfferRepository.save(BackupOffer.create(auction, rank2, 20000L));
        flushAndClear();

        backupOfferCommandService.decline(offer.getId(), rank2.getId());

        assertThatThrownBy(() -> backupOfferCommandService.decline(offer.getId(), rank2.getId()))
                .isInstanceOf(BackupOfferAlreadyResolvedException.class);
        assertThat(backupOfferRepository.count()).isEqualTo(2); // rank2(DECLINED) + rank3(WAITING), 중복 없음.
        // #75: 재실행(이미 DECLINED된 제안 재거절)은 예외를 던지고 BackupOffer 생성 로직에
        // 도달하지 않으므로 최초 decline이 만든 1건만 유지된다.
        assertThat(notificationRepository.count()).isEqualTo(1);
    }
}
