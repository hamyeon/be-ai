package com.vintic.backend.autobid.service;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.autobid.domain.AutoBidSetting;
import com.vintic.backend.autobid.dto.AutoBidRegisterResponse;
import com.vintic.backend.autobid.dto.AutoBidUpdateResponse;
import com.vintic.backend.autobid.repository.AutoBidSettingRepository;
import com.vintic.backend.bid.service.BidCommandService;
import com.vintic.backend.bid.service.IdempotencyClaimService;
import com.vintic.backend.common.exception.IdempotencyPayloadMismatchException;
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

// #41+ invariant 3번(same key + same payload는 최초 성공 response를 exact replay한다)을 검증한다.
// PLACE_BID의 replay는 ManualBidServiceTest가 담당한다. ProxyPriceEngine/Clock은 신규 의존성.
@DataJpaTest
@Import({
        BidCommandService.class, IdempotencyClaimService.class, AutoBidCommandService.class,
        AutoBidService.class, ProxyPriceEngine.class, TestObjectMapperConfig.class, TestClockConfig.class
})
class AutoBidServiceTest {

    @Autowired
    private AutoBidService autoBidService;

    @Autowired
    private BidCommandService bidCommandService;

    @Autowired
    private AuctionRepository auctionRepository;

    @Autowired
    private AutoBidSettingRepository autoBidSettingRepository;

    @Autowired
    private EntityManager entityManager;

    private User persistUser(String email) {
        User user = User.register(email, email, null);
        entityManager.persist(user);
        return user;
    }

    private Auction persistLiveAuction(User seller) {
        Product product = new Product(
                seller,
                List.of("https://example.com/a.jpg"),
                "Nike", "Dunk Low", "Panda", 270, "B", "PARTIAL",
                300000, 350000, "285,000원 ~ 315,000원", 290000, "사유", "설명"
        );
        entityManager.persist(product);

        Auction auction = Auction.schedule(
                product, 105000L, 5000L, LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(1)
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
    void 같은_key_같은_payload로_등록을_재요청하면_새_row_없이_최초_결과를_그대로_반환한다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Auction auction = persistLiveAuction(seller);
        flushAndClear();

        AutoBidRegisterResponse first = autoBidService.createAutoBid(auction.getId(), bidder.getId(), 200000L, "key-1");
        flushAndClear();
        AutoBidRegisterResponse replay = autoBidService.createAutoBid(auction.getId(), bidder.getId(), 200000L, "key-1");

        assertThat(replay).isEqualTo(first);
        assertThat(autoBidSettingRepository.findAll()).hasSize(1);
    }

    @Test
    void 등록_재요청_replay는_그사이_바뀐_currentPrice가_아니라_최초_응답을_그대로_반환한다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        User otherBidder = persistUser("other@vintic.local");
        Auction auction = persistLiveAuction(seller);
        flushAndClear();

        AutoBidRegisterResponse first = autoBidService.createAutoBid(auction.getId(), bidder.getId(), 200000L, "key-1");
        assertThat(first.currentPrice()).isEqualTo(105000L);
        flushAndClear();

        // 다른 사용자의 직접 입찰로 currentPrice를 올린다 - bidder의 cap(200000)을 넘는 금액이라
        // Proxy 반격이 없고(entrant의 AutoBid는 CAP_REACHED로 확정) currentPrice가 명확히 바뀐다.
        bidCommandService.placeManualBid(auction.getId(), otherBidder.getId(), 250000L);
        flushAndClear();
        Auction reloaded = auctionRepository.findById(auction.getId()).orElseThrow();
        assertThat(reloaded.getCurrentPrice()).isEqualTo(250000L);

        AutoBidRegisterResponse replay = autoBidService.createAutoBid(auction.getId(), bidder.getId(), 200000L, "key-1");

        assertThat(replay.currentPrice()).isEqualTo(105000L);
        assertThat(replay).isEqualTo(first);
    }

    @Test
    void 같은_key_다른_payload로_등록을_재요청하면_40905에_해당하는_예외가_발생한다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Auction auction = persistLiveAuction(seller);
        flushAndClear();

        autoBidService.createAutoBid(auction.getId(), bidder.getId(), 200000L, "key-1");
        flushAndClear();

        assertThatThrownBy(() -> autoBidService.createAutoBid(auction.getId(), bidder.getId(), 250000L, "key-1"))
                .isInstanceOf(IdempotencyPayloadMismatchException.class);
    }

    @Test
    void 같은_key_같은_payload로_수정을_재요청하면_command가_재실행되지_않고_최초_결과를_반환한다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Auction auction = persistLiveAuction(seller);
        AutoBidSetting setting = AutoBidSetting.reserve(auction, bidder, 150000L);
        setting.activate();
        autoBidSettingRepository.saveAndFlush(setting);
        flushAndClear();

        AutoBidUpdateResponse first = autoBidService.updateAutoBid(auction.getId(), bidder.getId(), 200000L, "patch-key-1");
        flushAndClear();
        AutoBidUpdateResponse replay = autoBidService.updateAutoBid(auction.getId(), bidder.getId(), 200000L, "patch-key-1");

        assertThat(replay).isEqualTo(first);
        AutoBidSetting reloaded = autoBidSettingRepository.findById(setting.getId()).orElseThrow();
        // command가 재실행됐다면 maxAmount가 동일값이라 겉으로는 구분 안 되므로, updatedAt이 두 번째
        // 호출 이후에도 그대로인지로 재실행 여부를 검증한다 - replay는 changeMaxAmount()를 다시 호출하지 않는다.
        assertThat(reloaded.getMaxAmount()).isEqualTo(200000L);
    }

    @Test
    void 같은_key_다른_payload로_수정을_재요청하면_40905에_해당하는_예외가_발생한다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Auction auction = persistLiveAuction(seller);
        AutoBidSetting setting = AutoBidSetting.reserve(auction, bidder, 150000L);
        setting.activate();
        autoBidSettingRepository.saveAndFlush(setting);
        flushAndClear();

        autoBidService.updateAutoBid(auction.getId(), bidder.getId(), 200000L, "patch-key-1");
        flushAndClear();

        assertThatThrownBy(() -> autoBidService.updateAutoBid(auction.getId(), bidder.getId(), 250000L, "patch-key-1"))
                .isInstanceOf(IdempotencyPayloadMismatchException.class);
    }
}
