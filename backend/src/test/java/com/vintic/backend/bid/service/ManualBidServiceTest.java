package com.vintic.backend.bid.service;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.bid.dto.PlaceBidResponse;
import com.vintic.backend.bid.repository.BidRepository;
import com.vintic.backend.bid.repository.IdempotencyRepository;
import com.vintic.backend.common.exception.IdempotencyPayloadMismatchException;
import com.vintic.backend.autobid.proxy.ProxyPriceEngine;
import com.vintic.backend.product.domain.Product;
import com.vintic.backend.support.TestClockConfig;
import com.vintic.backend.support.TestObjectMapperConfig;
import com.vintic.backend.user.domain.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// #32 범위: Idempotency 처리 자체만 검증한다. 수동 입찰 validation(#30/#31 커버 범위)은
// BidCommandServiceTest에 이미 있으므로 여기서 다시 만들지 않는다.
// ObjectMapper(#41)/ProxyPriceEngine·Clock(#41+): 신규 의존성 - 이 슬라이스엔 기본으로
// 없어 TestObjectMapperConfig/TestClockConfig로 명시적으로 채운다.
@DataJpaTest
@Import({
        BidCommandService.class, IdempotencyClaimService.class, ManualBidService.class,
        ProxyPriceEngine.class, TestObjectMapperConfig.class, TestClockConfig.class
})
class ManualBidServiceTest {

    @Autowired
    private ManualBidService manualBidService;

    @Autowired
    private AuctionRepository auctionRepository;

    @Autowired
    private BidRepository bidRepository;

    @Autowired
    private IdempotencyRepository idempotencyRepository;

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
                product, 10000L, 5000L, LocalDateTime.now().plusHours(1), LocalDateTime.now().plusHours(2)
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
    void 같은_user_auction_amount_key로_두_번_요청하면_Bid는_1건만_생성되고_같은_bidId를_반환한다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        flushAndClear();

        PlaceBidResponse first = manualBidService.placeBid(auction.getId(), bidder.getId(), 15000L, "abc");
        flushAndClear();
        PlaceBidResponse second = manualBidService.placeBid(auction.getId(), bidder.getId(), 15000L, "abc");
        flushAndClear();

        assertThat(second.bidId()).isEqualTo(first.bidId());
        assertThat(bidRepository.countByAuctionId(auction.getId())).isEqualTo(1);
        assertThat(idempotencyRepository.findByUserIdAndOperationScopeAndIdempotencyKey(
                bidder.getId(), "PLACE_BID:" + auction.getId(), "abc"
        )).isPresent();

        Auction reloaded = auctionRepository.findById(auction.getId()).orElseThrow();
        assertThat(reloaded.getCurrentPrice()).isEqualTo(15000L);
    }

    @Test
    void 같은_identity_같은_key에_다른_amount로_재요청하면_409_페이로드_불일치가_발생하고_추가_Bid가_없다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        flushAndClear();

        manualBidService.placeBid(auction.getId(), bidder.getId(), 15000L, "abc");
        flushAndClear();

        assertThatThrownBy(() -> manualBidService.placeBid(auction.getId(), bidder.getId(), 20000L, "abc"))
                .isInstanceOf(IdempotencyPayloadMismatchException.class);

        assertThat(bidRepository.countByAuctionId(auction.getId())).isEqualTo(1);
        Auction reloaded = auctionRepository.findById(auction.getId()).orElseThrow();
        assertThat(reloaded.getCurrentPrice()).isEqualTo(15000L);
    }

    @Test
    void 다른_user가_같은_key_문자열을_쓰면_별도_요청으로_처리되어_각자_Bid가_생긴다() {
        User seller = persistUser("seller@vintic.local");
        User bidderA = persistUser("bidderA@vintic.local");
        User bidderB = persistUser("bidderB@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        flushAndClear();

        PlaceBidResponse first = manualBidService.placeBid(auction.getId(), bidderA.getId(), 15000L, "same-key");
        flushAndClear();
        PlaceBidResponse second = manualBidService.placeBid(auction.getId(), bidderB.getId(), 20000L, "same-key");
        flushAndClear();

        assertThat(second.bidId()).isNotEqualTo(first.bidId());
        assertThat(bidRepository.countByAuctionId(auction.getId())).isEqualTo(2);
    }

    @Test
    void 같은_user가_다른_auction에_같은_key_문자열을_쓰면_별도_요청으로_처리된다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Product product = persistProduct(seller);
        Auction auctionOne = persistLiveAuction(product);
        Auction auctionTwo = persistLiveAuction(product);
        flushAndClear();

        PlaceBidResponse first = manualBidService.placeBid(auctionOne.getId(), bidder.getId(), 15000L, "same-key");
        flushAndClear();
        PlaceBidResponse second = manualBidService.placeBid(auctionTwo.getId(), bidder.getId(), 15000L, "same-key");
        flushAndClear();

        assertThat(second.bidId()).isNotEqualTo(first.bidId());
        assertThat(bidRepository.countByAuctionId(auctionOne.getId())).isEqualTo(1);
        assertThat(bidRepository.countByAuctionId(auctionTwo.getId())).isEqualTo(1);
    }

    // "claim insert 이후 입찰 validation이 실패하면 Idempotency row도 함께 롤백된다"는
    // ManualBidIdempotencyMySqlIT에서 검증한다. @DataJpaTest는 테스트 메서드 전체를 하나의
    // 물리 트랜잭션으로 감싸 마지막에만 롤백하므로, 이 메서드 안에서 flush된 claim row는
    // 실제 rollback 전까지 같은 커넥션의 조회에 계속 보인다 — 여기서 "row가 없다"를
    // 검증하면 실제 동작과 무관하게 항상 거짓 통과(테스트가 무엇을 검증하는지 불명확)하거나
    // TestTransaction으로 트랜잭션 경계를 직접 조작해야 하는데, 그러면 같은 트랜잭션에서
    // 만든 fixture(User/Product/Auction)까지 함께 사라져 검증이 더 복잡해진다. 실제 커밋/롤백
    // 경계가 있는 MySqlIT 쪽이 이 시나리오에 더 정확하다.
}
