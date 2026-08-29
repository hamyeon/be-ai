package com.vintic.backend.autobid.repository;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.autobid.domain.AutoBidSetting;
import com.vintic.backend.product.domain.Product;
import com.vintic.backend.user.domain.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
class AutoBidSettingRepositoryTest {

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

    @Test
    void 같은_경매에_같은_유저의_현재_설정을_두_번_저장할_수_없다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Auction auction = persistAuction(seller);

        autoBidSettingRepository.saveAndFlush(AutoBidSetting.reserve(auction, bidder, 100000L));

        assertThatThrownBy(() ->
                autoBidSettingRepository.saveAndFlush(AutoBidSetting.reserve(auction, bidder, 200000L))
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void CANCELED_이력은_여러_건_저장할_수_있다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Auction auction = persistAuction(seller);

        AutoBidSetting first = AutoBidSetting.reserve(auction, bidder, 100000L);
        first.cancel();
        autoBidSettingRepository.saveAndFlush(first);

        AutoBidSetting second = AutoBidSetting.reserve(auction, bidder, 120000L);
        second.cancel();
        autoBidSettingRepository.saveAndFlush(second);

        assertThat(autoBidSettingRepository.findAll()).hasSize(2);
    }

    @Test
    void CANCELED_이후_새로운_현재_설정을_저장할_수_있다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Auction auction = persistAuction(seller);

        AutoBidSetting canceled = AutoBidSetting.reserve(auction, bidder, 100000L);
        canceled.cancel();
        autoBidSettingRepository.saveAndFlush(canceled);

        AutoBidSetting reregistered = AutoBidSetting.reserve(auction, bidder, 150000L);
        autoBidSettingRepository.saveAndFlush(reregistered);
        entityManager.clear();

        Optional<AutoBidSetting> current = autoBidSettingRepository
                .findByAuctionIdAndUserIdAndActiveSlotTrue(auction.getId(), bidder.getId());

        assertThat(current).isPresent();
        assertThat(current.get().getMaxAmount()).isEqualTo(150000L);
    }

    @Test
    void findByAuctionIdAndUserIdAndActiveSlotTrue로_현재_설정을_조회할_수_있다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Auction auction = persistAuction(seller);

        autoBidSettingRepository.saveAndFlush(AutoBidSetting.reserve(auction, bidder, 100000L));
        entityManager.clear();

        Optional<AutoBidSetting> found = autoBidSettingRepository
                .findByAuctionIdAndUserIdAndActiveSlotTrue(auction.getId(), bidder.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getMaxAmount()).isEqualTo(100000L);
    }

    @Test
    void findByAuctionIdAndUserIdAndActiveSlotTrue는_설정이_없으면_빈값을_반환한다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Auction auction = persistAuction(seller);
        entityManager.flush();
        entityManager.clear();

        Optional<AutoBidSetting> found = autoBidSettingRepository
                .findByAuctionIdAndUserIdAndActiveSlotTrue(auction.getId(), bidder.getId());

        assertThat(found).isEmpty();
    }

    @Test
    void findByAuctionIdAndUserIdAndActiveSlotTrue는_CANCELED만_있으면_빈값을_반환한다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Auction auction = persistAuction(seller);

        AutoBidSetting canceled = AutoBidSetting.reserve(auction, bidder, 100000L);
        canceled.cancel();
        autoBidSettingRepository.saveAndFlush(canceled);
        entityManager.clear();

        Optional<AutoBidSetting> found = autoBidSettingRepository
                .findByAuctionIdAndUserIdAndActiveSlotTrue(auction.getId(), bidder.getId());

        assertThat(found).isEmpty();
    }
}
