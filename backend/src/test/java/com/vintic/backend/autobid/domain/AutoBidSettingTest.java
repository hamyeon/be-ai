package com.vintic.backend.autobid.domain;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.common.exception.InvalidAutoBidSettingStatusException;
import com.vintic.backend.product.domain.Product;
import com.vintic.backend.user.domain.User;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AutoBidSettingTest {

    private final User seller = User.register("seller@vintic.local", "seller", null);
    private final User bidder = User.register("bidder@vintic.local", "bidder", null);
    private final Product product = new Product(
            seller,
            List.of("https://example.com/a.jpg"),
            "Nike", "Dunk Low", "Panda", 270, "B", "PARTIAL",
            300000, 350000, "285,000원 ~ 315,000원", 290000, "사유", "설명"
    );
    private final Auction auction = Auction.schedule(
            product, 10000L, 5000L, LocalDateTime.now().plusHours(1), LocalDateTime.now().plusHours(2)
    );

    @Test
    void 생성하면_RESERVED_상태다() {
        AutoBidSetting setting = AutoBidSetting.reserve(auction, bidder, 100000L);

        assertThat(setting.getStatus()).isEqualTo(AutoBidSettingStatus.RESERVED);
    }

    @Test
    void 최대금액이_0이하면_생성에_실패한다() {
        assertThatThrownBy(() -> AutoBidSetting.reserve(auction, bidder, 0L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void RESERVED에서_ACTIVE로_전이할_수_있다() {
        AutoBidSetting setting = AutoBidSetting.reserve(auction, bidder, 100000L);

        setting.activate();

        assertThat(setting.getStatus()).isEqualTo(AutoBidSettingStatus.ACTIVE);
    }

    @Test
    void RESERVED에서_CANCELED로_전이할_수_있다() {
        AutoBidSetting setting = AutoBidSetting.reserve(auction, bidder, 100000L);

        setting.cancel();

        assertThat(setting.getStatus()).isEqualTo(AutoBidSettingStatus.CANCELED);
    }

    @Test
    void ACTIVE에서_EXHAUSTED로_전이할_수_있다() {
        AutoBidSetting setting = AutoBidSetting.reserve(auction, bidder, 100000L);
        setting.activate();

        setting.exhaust();

        assertThat(setting.getStatus()).isEqualTo(AutoBidSettingStatus.EXHAUSTED);
    }

    @Test
    void ACTIVE에서_CANCELED로_전이할_수_있다() {
        AutoBidSetting setting = AutoBidSetting.reserve(auction, bidder, 100000L);
        setting.activate();

        setting.cancel();

        assertThat(setting.getStatus()).isEqualTo(AutoBidSettingStatus.CANCELED);
    }

    @Test
    void RESERVED가_아니면_activate할_수_없다() {
        AutoBidSetting setting = AutoBidSetting.reserve(auction, bidder, 100000L);
        setting.cancel();

        assertThatThrownBy(setting::activate)
                .isInstanceOf(InvalidAutoBidSettingStatusException.class);
    }

    @Test
    void ACTIVE가_아니면_exhaust할_수_없다() {
        AutoBidSetting setting = AutoBidSetting.reserve(auction, bidder, 100000L);

        assertThatThrownBy(setting::exhaust)
                .isInstanceOf(InvalidAutoBidSettingStatusException.class);
    }

    @Test
    void EXHAUSTED는_terminal_상태라_추가_전이가_불가능하다() {
        AutoBidSetting setting = AutoBidSetting.reserve(auction, bidder, 100000L);
        setting.activate();
        setting.exhaust();

        assertThatThrownBy(setting::activate).isInstanceOf(InvalidAutoBidSettingStatusException.class);
        assertThatThrownBy(setting::exhaust).isInstanceOf(InvalidAutoBidSettingStatusException.class);
        assertThatThrownBy(setting::cancel).isInstanceOf(InvalidAutoBidSettingStatusException.class);
    }

    @Test
    void CANCELED는_terminal_상태라_추가_전이가_불가능하다() {
        AutoBidSetting setting = AutoBidSetting.reserve(auction, bidder, 100000L);
        setting.cancel();

        assertThatThrownBy(setting::activate).isInstanceOf(InvalidAutoBidSettingStatusException.class);
        assertThatThrownBy(setting::exhaust).isInstanceOf(InvalidAutoBidSettingStatusException.class);
        assertThatThrownBy(setting::cancel).isInstanceOf(InvalidAutoBidSettingStatusException.class);
    }
}
