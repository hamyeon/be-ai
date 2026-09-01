package com.vintic.backend.auction.service;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.domain.AuctionResult;
import com.vintic.backend.auction.domain.AuctionStatus;
import com.vintic.backend.auction.dto.AuctionResultResponse;
import com.vintic.backend.auction.repository.AuctionRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

// #73-2: LIVE -> ENDED lifecycle 전환 + #56 AuctionSettlementService 연결.
@DataJpaTest
@Import({AuctionEndService.class, AuctionSettlementService.class, AuctionResultQueryService.class, TestClockConfig.class})
class AuctionEndServiceTest {

    private static final LocalDateTime FIXED_NOW = LocalDateTime.ofInstant(TestClockConfig.FIXED_INSTANT, ClockConfig.APP_ZONE);

    @Autowired
    private AuctionEndService auctionEndService;

    @Autowired
    private AuctionResultQueryService auctionResultQueryService;

    @Autowired
    private AuctionRepository auctionRepository;

    @Autowired
    private OrderRepository orderRepository;

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

    // startAt/endAt은 호출자가 직접 지정한다 - 연장 시나리오를 정확히 통제하기 위해서다.
    private Auction persistLiveAuction(Product product, LocalDateTime startAt, LocalDateTime endAt) {
        Auction auction = Auction.schedule(product, 10000L, 5000L, startAt, endAt);
        auction.start();
        entityManager.persist(auction);
        return auction;
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void endAt_이전이면_LIVE로_유지된다() {
        User seller = persistUser("seller@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product, FIXED_NOW.minusHours(1), FIXED_NOW.plusMinutes(1));
        flushAndClear();

        auctionEndService.endIfDue(auction.getId());

        Auction reloaded = auctionRepository.findById(auction.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AuctionStatus.LIVE);
        assertThat(orderRepository.count()).isZero();
    }

    @Test
    void endAt_도달하면_ENDED로_전환된다() {
        User seller = persistUser("seller@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product, FIXED_NOW.minusHours(2), FIXED_NOW.minusMinutes(1));
        flushAndClear();

        auctionEndService.endIfDue(auction.getId());

        Auction reloaded = auctionRepository.findById(auction.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AuctionStatus.ENDED);
    }

    @Test
    void 연장으로_늦춰진_최신_endAt_전이면_원래_endAt이_지났어도_LIVE로_유지된다() {
        User seller = persistUser("seller@vintic.local");
        Product product = persistProduct(seller);
        // 원래 endAt은 이미 지났지만(now - 30초), 연장 트리거 윈도우 안에서 실제로 연장되어
        // 최신 endAt은 now + 2분 30초(3분 연장 - 30초 경과분)가 된다 - 아직 미래다.
        LocalDateTime originalEndAt = FIXED_NOW.minusSeconds(30);
        Auction auction = persistLiveAuction(product, FIXED_NOW.minusHours(2), originalEndAt);
        auction.maybeExtend(originalEndAt.minusSeconds(10));
        flushAndClear();

        auctionEndService.endIfDue(auction.getId());

        Auction reloaded = auctionRepository.findById(auction.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AuctionStatus.LIVE);
        assertThat(reloaded.getEndAt()).isAfter(FIXED_NOW);
    }

    @Test
    void 연장_이후에도_최신_endAt에_도달하면_ENDED로_전환된다() {
        User seller = persistUser("seller@vintic.local");
        Product product = persistProduct(seller);
        // 원래 endAt(now-8분)에서 3분 연장돼도 최신 endAt(now-5분)은 여전히 과거다 - 연장된
        // "최신" 값 기준으로도 종료 대상이어야 한다(연장 이후의 값을 재확인한다는 것 자체를
        // 증명하는 케이스 - 연장을 거치지 않은 endAt_도달 케이스와는 다른 경로).
        LocalDateTime originalEndAt = FIXED_NOW.minusMinutes(8);
        Auction auction = persistLiveAuction(product, FIXED_NOW.minusHours(2), originalEndAt);
        auction.maybeExtend(originalEndAt.minusSeconds(30));
        flushAndClear();

        auctionEndService.endIfDue(auction.getId());

        Auction reloaded = auctionRepository.findById(auction.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AuctionStatus.ENDED);
    }

    @Test
    void 이미_ENDED인_경매는_재처리하지_않는다() {
        User seller = persistUser("seller@vintic.local");
        User winner = persistUser("winner@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product, FIXED_NOW.minusHours(2), FIXED_NOW.minusMinutes(1));
        auction.placeManualBid(winner, 30000L);
        auction.end();
        flushAndClear();

        auctionEndService.endIfDue(auction.getId());

        Auction reloaded = auctionRepository.findById(auction.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AuctionStatus.ENDED);
        // end() 전에 settle() 계약(winner Order 생성)을 아예 타지 않았으므로 Order가 없다 -
        // endIfDue()가 재처리하지 않고 조용히 skip했다는 뜻이다.
        assertThat(orderRepository.count()).isZero();
    }

    @Test
    void 이미_CANCELED인_경매는_재처리하지_않는다() {
        User seller = persistUser("seller@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = Auction.schedule(product, 10000L, 5000L, FIXED_NOW.plusMinutes(1), FIXED_NOW.plusHours(1));
        auction.cancel();
        entityManager.persist(auction);
        flushAndClear();

        auctionEndService.endIfDue(auction.getId());

        Auction reloaded = auctionRepository.findById(auction.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AuctionStatus.CANCELED);
        assertThat(orderRepository.count()).isZero();
    }

    @Test
    void 존재하지_않는_경매를_처리해도_예외없이_아무일도_일어나지_않는다() {
        auctionEndService.endIfDue(9999L);
    }

    @Test
    void 입찰이_없으면_Order를_생성하지_않는다() {
        User seller = persistUser("seller@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product, FIXED_NOW.minusHours(2), FIXED_NOW.minusMinutes(1));
        flushAndClear();

        auctionEndService.endIfDue(auction.getId());

        Auction reloaded = auctionRepository.findById(auction.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AuctionStatus.ENDED);
        assertThat(orderRepository.count()).isZero();
    }

    @Test
    void 낙찰자가_있으면_PAYMENT_PENDING_Order를_정확히_1건_생성하고_result가_WON을_반환한다() {
        User seller = persistUser("seller@vintic.local");
        User winner = persistUser("winner@vintic.local");
        Product product = persistProduct(seller);
        LocalDateTime endAt = FIXED_NOW.minusMinutes(1);
        Auction auction = persistLiveAuction(product, FIXED_NOW.minusHours(2), endAt);
        auction.placeManualBid(winner, 30000L);
        bidRepository.save(Bid.place(auction, winner, 30000L, BidType.MANUAL));
        flushAndClear();

        auctionEndService.endIfDue(auction.getId());

        assertThat(orderRepository.count()).isEqualTo(1);
        Order order = orderRepository.findByAuctionIdAndBuyerId(auction.getId(), winner.getId()).orElseThrow();
        assertThat(order.getPurchasePrice()).isEqualTo(30000L); // = auction.finalPrice(currentPrice)
        assertThat(order.getPaymentDeadline()).isCloseTo(endAt.plusHours(24), within(1, ChronoUnit.SECONDS));

        // /result는 Result entity 없이 Auction/Order 상태로부터 매번 계산한다 - 종료 후에도
        // 그 구조가 그대로 WON을 만들어내는지 확인한다(#56 derived state 구조 재사용 확인).
        AuctionResultResponse result = auctionResultQueryService.getResult(auction.getId(), winner.getId());
        assertThat(result.result()).isEqualTo(AuctionResult.WON);
        assertThat(result.orderId()).isEqualTo(order.getId());
    }

    @Test
    void 반복_호출해도_Order가_중복_생성되지_않는다() {
        User seller = persistUser("seller@vintic.local");
        User winner = persistUser("winner@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product, FIXED_NOW.minusHours(2), FIXED_NOW.minusMinutes(1));
        auction.placeManualBid(winner, 30000L);
        flushAndClear();

        auctionEndService.endIfDue(auction.getId());
        auctionEndService.endIfDue(auction.getId());
        auctionEndService.endIfDue(auction.getId());

        Auction reloaded = auctionRepository.findById(auction.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AuctionStatus.ENDED);
        assertThat(orderRepository.count()).isEqualTo(1);
    }
}
