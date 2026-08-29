package com.vintic.backend.autobid.service;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.autobid.domain.AutoBidSetting;
import com.vintic.backend.autobid.domain.AutoBidSettingStatus;
import com.vintic.backend.autobid.dto.AutoBidMeResponse;
import com.vintic.backend.autobid.repository.AutoBidSettingRepository;
import com.vintic.backend.common.exception.AutoBidNotFoundException;
import com.vintic.backend.product.domain.Product;
import com.vintic.backend.support.TestClockConfig;
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

// Clock(#41+): AutoBidQueryService의 신규 의존성 - TestClockConfig로 채운다.
@DataJpaTest
@Import({AutoBidQueryService.class, TestClockConfig.class})
class AutoBidQueryServiceTest {

    @Autowired
    private AutoBidQueryService autoBidQueryService;

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

    private Auction persistAuction(User seller) {
        Product product = new Product(
                seller,
                List.of("https://example.com/a.jpg"),
                "Nike", "Dunk Low", "Panda", 270, "B", "PARTIAL",
                300000, 350000, "285,000원 ~ 315,000원", 290000, "사유", "설명"
        );
        entityManager.persist(product);

        Auction auction = Auction.schedule(
                product, 10000L, 5000L, LocalDateTime.now().plusHours(1), LocalDateTime.now().plusHours(2)
        );
        entityManager.persist(auction);
        return auction;
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void RESERVED_설정을_조회할_수_있다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Auction auction = persistAuction(seller);
        autoBidSettingRepository.saveAndFlush(AutoBidSetting.reserve(auction, bidder, 100000L));
        flushAndClear();

        AutoBidMeResponse response = autoBidQueryService.getMyAutoBid(auction.getId(), bidder.getId());

        assertThat(response.status()).isEqualTo(AutoBidSettingStatus.RESERVED);
        assertThat(response.maxAmount()).isEqualTo(100000L);
        assertThat(response.currentPrice()).isEqualTo(10000L);
        assertThat(response.minCapAmount()).isEqualTo(15000L);
        assertThat(response.canModify()).isTrue();
        assertThat(response.canCancel()).isTrue();
        assertThat(response.serverTime()).isNotNull();
    }

    @Test
    void ACTIVE_설정을_조회할_수_있다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Auction auction = persistAuction(seller);
        AutoBidSetting setting = AutoBidSetting.reserve(auction, bidder, 100000L);
        setting.activate();
        autoBidSettingRepository.saveAndFlush(setting);
        flushAndClear();

        AutoBidMeResponse response = autoBidQueryService.getMyAutoBid(auction.getId(), bidder.getId());

        assertThat(response.status()).isEqualTo(AutoBidSettingStatus.ACTIVE);
        assertThat(response.canModify()).isTrue();
        assertThat(response.canCancel()).isTrue();
    }

    @Test
    void CAP_REACHED_설정을_조회할_수_있다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Auction auction = persistAuction(seller);
        AutoBidSetting setting = AutoBidSetting.reserve(auction, bidder, 100000L);
        setting.activate();
        setting.markCapReached();
        autoBidSettingRepository.saveAndFlush(setting);
        flushAndClear();

        AutoBidMeResponse response = autoBidQueryService.getMyAutoBid(auction.getId(), bidder.getId());

        assertThat(response.status()).isEqualTo(AutoBidSettingStatus.CAP_REACHED);
        assertThat(response.canModify()).isTrue();
        assertThat(response.canCancel()).isTrue();
    }

    @Test
    void 현재_설정이_없으면_40404에_해당하는_예외가_발생한다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Auction auction = persistAuction(seller);
        flushAndClear();

        assertThatThrownBy(() -> autoBidQueryService.getMyAutoBid(auction.getId(), bidder.getId()))
                .isInstanceOf(AutoBidNotFoundException.class);
    }

    @Test
    void 과거_CANCELED만_있으면_40404에_해당하는_예외가_발생한다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Auction auction = persistAuction(seller);
        AutoBidSetting canceled = AutoBidSetting.reserve(auction, bidder, 100000L);
        canceled.cancel();
        autoBidSettingRepository.saveAndFlush(canceled);
        flushAndClear();

        assertThatThrownBy(() -> autoBidQueryService.getMyAutoBid(auction.getId(), bidder.getId()))
                .isInstanceOf(AutoBidNotFoundException.class);
    }
}
