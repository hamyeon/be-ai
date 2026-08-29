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

    // #35: PESSIMISTIC_WRITE 자체가 정상 Auction을 반환하는지만 확인한다. 두 트랜잭션이
    // 실제로 서로를 블로킹하는지는 이 단일 커넥션 @DataJpaTest로 검증할 수 없고,
    // ManualBidConcurrencyRaceIT(#35 20회 본 실험)의 실제 MySQL 결과(0/20 invariant
    // violation, CannotAcquireLockException 0/160)로 확인한다 — 별도 락 대기 테스트를
    // 새로 만들지 않는다.
    @Test
    void findByIdForUpdate로_경매를_조회할_수_있다() {
        Product product = persistProduct();
        Auction auction = Auction.schedule(
                product, 10000L, 5000L, LocalDateTime.now().plusHours(1), LocalDateTime.now().plusHours(2)
        );

        Auction saved = auctionRepository.save(auction);
        entityManager.flush();
        entityManager.clear();

        Optional<Auction> found = auctionRepository.findByIdForUpdate(saved.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(saved.getId());
    }

    @Test
    void findByIdWithProductAndWinner로_product_seller_currentWinner를_함께_조회할_수_있다() {
        Product product = persistProduct();
        User bidder = User.register("bidder@vintic.local", "bidder", null);
        entityManager.persist(bidder);
        Auction auction = Auction.schedule(
                product, 10000L, 5000L, LocalDateTime.now().plusHours(1), LocalDateTime.now().plusHours(2)
        );
        auction.start();
        auction.placeManualBid(bidder, 15000L);
        Auction saved = auctionRepository.save(auction);
        entityManager.flush();
        entityManager.clear();

        Optional<Auction> found = auctionRepository.findByIdWithProductAndWinner(saved.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getProduct().getSeller().getId()).isEqualTo(product.getSeller().getId());
        assertThat(found.get().getCurrentWinner().getId()).isEqualTo(bidder.getId());
    }

    @Test
    void findByIdWithProductAndWinner는_최고입찰자가_없으면_currentWinner가_null이다() {
        Product product = persistProduct();
        Auction auction = Auction.schedule(
                product, 10000L, 5000L, LocalDateTime.now().plusHours(1), LocalDateTime.now().plusHours(2)
        );

        Auction saved = auctionRepository.save(auction);
        entityManager.flush();
        entityManager.clear();

        Optional<Auction> found = auctionRepository.findByIdWithProductAndWinner(saved.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getCurrentWinner()).isNull();
    }
}
