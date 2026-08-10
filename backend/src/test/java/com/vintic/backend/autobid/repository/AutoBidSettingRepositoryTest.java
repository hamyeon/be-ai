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

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
class AutoBidSettingRepositoryTest {

    @Autowired
    private AutoBidSettingRepository autoBidSettingRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void 같은_경매에_같은_유저의_자동입찰_설정을_두_번_저장할_수_없다() {
        User seller = User.register("seller@vintic.local", "seller", null);
        entityManager.persist(seller);
        User bidder = User.register("bidder@vintic.local", "bidder", null);
        entityManager.persist(bidder);

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

        autoBidSettingRepository.saveAndFlush(AutoBidSetting.reserve(auction, bidder, 100000L));

        assertThatThrownBy(() ->
                autoBidSettingRepository.saveAndFlush(AutoBidSetting.reserve(auction, bidder, 200000L))
        ).isInstanceOf(DataIntegrityViolationException.class);
    }
}
