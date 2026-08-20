package com.vintic.backend.auction.repository;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.domain.AuctionStatus;
import com.vintic.backend.product.domain.Product;
import com.vintic.backend.user.domain.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class AuctionRepositoryTest {

    @Autowired
    private AuctionRepository auctionRepository;

    @Autowired
    private EntityManager entityManager;

    private Product persistProduct() {
        User seller = User.register("seller@vintic.local", "seller", null);
        entityManager.persist(seller);

        Product product = new Product(
                seller,
                List.of("https://example.com/a.jpg"),
                "Nike", "Dunk Low", "Panda", 270, "B", "PARTIAL",
                300000, 350000, "285,000원 ~ 315,000원", 290000, "사유", "설명"
        );
        entityManager.persist(product);
        return product;
    }

    @Test
    void 경매를_저장하고_id로_조회할_수_있다() {
        Product product = persistProduct();
        Auction auction = Auction.schedule(
                product, 10000L, 5000L, LocalDateTime.now().plusHours(1), LocalDateTime.now().plusHours(2)
        );

        Auction saved = auctionRepository.save(auction);
        entityManager.flush();
        entityManager.clear();

        Optional<Auction> found = auctionRepository.findById(saved.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getStatus()).isEqualTo(AuctionStatus.SCHEDULED);
        assertThat(found.get().getProduct().getId()).isEqualTo(product.getId());
        assertThat(found.get().getCurrentWinner()).isNull();
    }
}
