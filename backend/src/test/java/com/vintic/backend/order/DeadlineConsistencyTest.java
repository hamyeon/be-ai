package com.vintic.backend.order;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.dto.AuctionResultResponse;
import com.vintic.backend.auction.service.AuctionResultQueryService;
import com.vintic.backend.backupoffer.domain.BackupOffer;
import com.vintic.backend.backupoffer.dto.BackupOfferAcceptResponse;
import com.vintic.backend.backupoffer.repository.BackupOfferRepository;
import com.vintic.backend.backupoffer.service.BackupOfferCommandService;
import com.vintic.backend.bid.domain.Bid;
import com.vintic.backend.bid.domain.BidType;
import com.vintic.backend.bid.repository.BidRepository;
import com.vintic.backend.config.ClockConfig;
import com.vintic.backend.order.domain.Order;
import com.vintic.backend.order.repository.OrderRepository;
import com.vintic.backend.order.service.AuctionSettlementService;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

// FINAL contract §0.10 - 이번 이슈(#57-1)가 명시적으로 고정하라고 요구한 4개 기한 계약. 신규
// production 로직은 없다 - AuctionSettlementService(#56-1)/BackupOffer.create()(#56-2)/
// BackupOfferCommandService.accept()(#56-3)가 이미 구현한 공식을 감사(audit)만 한다. 각 공식
// 자체는 AuctionSettlementServiceTest/BackupOfferCommandServiceTest에 이미 개별적으로 흩어져
// 있지만, 이 클래스는 §0.10 4개 항목을 한 곳에 모아 "계약이 요구하는 형태 그대로" 고정한다.
@DataJpaTest
@Import({AuctionSettlementService.class, AuctionResultQueryService.class, BackupOfferCommandService.class, TestClockConfig.class})
class DeadlineConsistencyTest {

    private static final LocalDateTime FIXED_NOW = LocalDateTime.ofInstant(TestClockConfig.FIXED_INSTANT, ClockConfig.APP_ZONE);

    @Autowired
    private AuctionSettlementService auctionSettlementService;

    @Autowired
    private AuctionResultQueryService auctionResultQueryService;

    @Autowired
    private BackupOfferCommandService backupOfferCommandService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private BackupOfferRepository backupOfferRepository;

    @Autowired
    private BidRepository bidRepository;

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

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void 낙찰자_결제기한은_endsAt_플러스_24시간이고_result와_Order가_동일한_값을_반환한다() {
        User seller = persistUser("seller@vintic.local");
        User winner = persistUser("winner@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = Auction.schedule(
                product, 10000L, 5000L, LocalDateTime.now().minusHours(2), LocalDateTime.now().minusHours(1)
        );
        auction.start();
        entityManager.persist(auction);
        auction.placeManualBid(winner, 30000L);
        bidRepository.save(Bid.place(auction, winner, 30000L, BidType.MANUAL));
        auction.end();
        flushAndClear();

        Optional<Order> settled = auctionSettlementService.settle(auction.getId());
        flushAndClear();

        Order order = orderRepository.findByAuctionIdAndBuyerId(auction.getId(), winner.getId()).orElseThrow();
        // 계약: 낙찰자 결제 기한 = auction.endsAt + 24h (§0.10). H2가 DB round-trip에서 nanosecond
        // 정밀도를 그대로 보존하지 않아(기존 AuctionSettlementServiceTest와 동일한 이유) 초 단위
        // 근접만 확인한다.
        assertThat(order.getPaymentDeadline()).isCloseTo(auction.getEndAt().plusHours(24), within(1, ChronoUnit.SECONDS));

        AuctionResultResponse result = auctionResultQueryService.getResult(auction.getId(), winner.getId());
        // 계약: winner result.paymentDeadline == winner Order.paymentDeadline (§0.10) - 공식이
        // 아니라 두 응답이 실제로 같은 값을 가리키는지 직접 비교한다(둘 다 같은 DB round-trip을
        // 거친 값이라 정확히 같아야 한다).
        assertThat(result.paymentDeadline().toLocalDateTime()).isEqualTo(order.getPaymentDeadline());
        assertThat(settled).isPresent();
    }

    @Test
    void BackupOffer_기한은_생성시각_플러스_24시간이다() {
        User seller = persistUser("seller@vintic.local");
        User candidate = persistUser("candidate@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = Auction.schedule(
                product, 10000L, 5000L, LocalDateTime.now().minusHours(2), LocalDateTime.now().minusHours(1)
        );
        auction.start();
        auction.end();
        entityManager.persist(auction);
        flushAndClear();

        BackupOffer offer = BackupOffer.create(auction, candidate, 20000L);

        // 계약: 차순위 제안 응답 기한 = createdAt + 24h (§0.10).
        assertThat(offer.getDeadline()).isEqualTo(offer.getCreatedAt().plusHours(24));
    }

    @Test
    void BackupOffer_수락_Order_기한은_수락시각_플러스_24시간이다() {
        User seller = persistUser("seller@vintic.local");
        User winner = persistUser("winner@vintic.local");
        User candidate = persistUser("candidate@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = Auction.schedule(
                product, 10000L, 5000L, LocalDateTime.now().minusHours(2), LocalDateTime.now().minusHours(1)
        );
        auction.start();
        entityManager.persist(auction);
        bidRepository.save(Bid.place(auction, candidate, 20000L, BidType.MANUAL));
        auction.placeManualBid(candidate, 20000L);
        bidRepository.save(Bid.place(auction, winner, 25000L, BidType.MANUAL));
        auction.placeManualBid(winner, 25000L);
        auction.end();
        BackupOffer offer = backupOfferRepository.save(BackupOffer.create(auction, candidate, 20000L));
        flushAndClear();

        BackupOfferAcceptResponse response = backupOfferCommandService.accept(offer.getId());

        Order order = orderRepository.findByAuctionIdAndBuyerId(auction.getId(), candidate.getId()).orElseThrow();
        // 계약: 차순위 수락 후 결제 기한 = backupOffer.acceptedAt + 24h (§0.10) - 원 경매 endsAt,
        // 제안 자체의 deadline과 무관하다. FIXED_NOW가 TestClockConfig가 주입한 acceptedAt이다.
        assertThat(order.getPaymentDeadline()).isCloseTo(FIXED_NOW.plusHours(24), within(1, ChronoUnit.SECONDS));
        assertThat(response.paymentDeadline().toLocalDateTime()).isCloseTo(order.getPaymentDeadline(), within(1, ChronoUnit.SECONDS));
    }
}
