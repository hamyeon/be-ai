package com.vintic.backend.bid.repository;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.bid.domain.Bid;
import com.vintic.backend.bid.domain.BidType;
import com.vintic.backend.product.domain.Product;
import com.vintic.backend.user.domain.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
class BidRepositoryTest {

    @Autowired
    private BidRepository bidRepository;

    @Autowired
    private EntityManager entityManager;

    private User bidder;
    private Auction auction;

    private void setUpFixture() {
        User seller = User.register("seller@vintic.local", "seller", null);
        entityManager.persist(seller);
        bidder = User.register("bidder@vintic.local", "bidder", null);
        entityManager.persist(bidder);

        Product product = new Product(
                seller,
                List.of("https://example.com/a.jpg"),
                "Nike", "Dunk Low", "Panda", 270, "B", "PARTIAL",
                300000, 350000, "285,000원 ~ 315,000원", 290000, "사유", "설명"
        );
        entityManager.persist(product);

        auction = Auction.schedule(
                product, 10000L, 5000L, LocalDateTime.now().plusHours(1), LocalDateTime.now().plusHours(2)
        );
        entityManager.persist(auction);
    }

    @Test
    void 최신순_조회는_createdAt_DESC_id_DESC로_정렬된다() {
        setUpFixture();

        Bid first = Bid.place(auction, bidder, 15000L, BidType.MANUAL);
        entityManager.persist(first);
        Bid second = Bid.place(auction, bidder, 20000L, BidType.MANUAL);
        entityManager.persist(second);
        entityManager.flush();
        entityManager.clear();

        Page<Bid> page = bidRepository.findByAuctionIdOrderByCreatedAtDescIdDesc(
                auction.getId(), PageRequest.of(0, 20)
        );

        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getContent().get(0).getId()).isEqualTo(second.getId());
        assertThat(page.getContent().get(1).getId()).isEqualTo(first.getId());
    }

    @Test
    void 오래된순_조회는_createdAt_ASC_id_ASC로_정렬된다() {
        setUpFixture();

        Bid first = Bid.place(auction, bidder, 15000L, BidType.MANUAL);
        entityManager.persist(first);
        Bid second = Bid.place(auction, bidder, 20000L, BidType.MANUAL);
        entityManager.persist(second);
        entityManager.flush();
        entityManager.clear();

        Page<Bid> page = bidRepository.findByAuctionIdOrderByCreatedAtAscIdAsc(
                auction.getId(), PageRequest.of(0, 20)
        );

        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getContent().get(0).getId()).isEqualTo(first.getId());
        assertThat(page.getContent().get(1).getId()).isEqualTo(second.getId());
    }

    @Test
    void createdAt이_같아도_id로_안정적으로_정렬된다() {
        setUpFixture();

        Bid first = Bid.place(auction, bidder, 15000L, BidType.MANUAL);
        entityManager.persist(first);
        Bid second = Bid.place(auction, bidder, 20000L, BidType.MANUAL);
        entityManager.persist(second);
        entityManager.flush();

        // 두 입찰의 createdAt을 동일한 값으로 강제해 id 보조 정렬만으로 순서가 결정되는지 확인한다.
        entityManager.createNativeQuery("UPDATE bids SET created_at = :fixedTime WHERE auction_id = :auctionId")
                .setParameter("fixedTime", LocalDateTime.of(2026, 1, 1, 0, 0))
                .setParameter("auctionId", auction.getId())
                .executeUpdate();
        entityManager.clear();

        Page<Bid> latest = bidRepository.findByAuctionIdOrderByCreatedAtDescIdDesc(
                auction.getId(), PageRequest.of(0, 20)
        );
        Page<Bid> oldest = bidRepository.findByAuctionIdOrderByCreatedAtAscIdAsc(
                auction.getId(), PageRequest.of(0, 20)
        );

        assertThat(latest.getContent().get(0).getId()).isEqualTo(second.getId());
        assertThat(latest.getContent().get(1).getId()).isEqualTo(first.getId());
        assertThat(oldest.getContent().get(0).getId()).isEqualTo(first.getId());
        assertThat(oldest.getContent().get(1).getId()).isEqualTo(second.getId());
    }

    @Test
    void size만큼_페이지가_나뉘고_다음페이지가_있으면_hasNext는_true다() {
        setUpFixture();

        for (int i = 0; i < 3; i++) {
            entityManager.persist(Bid.place(auction, bidder, 15000L + i * 1000, BidType.MANUAL));
        }
        entityManager.flush();
        entityManager.clear();

        Page<Bid> firstPage = bidRepository.findByAuctionIdOrderByCreatedAtDescIdDesc(
                auction.getId(), PageRequest.of(0, 2)
        );
        Page<Bid> secondPage = bidRepository.findByAuctionIdOrderByCreatedAtDescIdDesc(
                auction.getId(), PageRequest.of(1, 2)
        );

        assertThat(firstPage.getContent()).hasSize(2);
        assertThat(firstPage.hasNext()).isTrue();
        assertThat(secondPage.getContent()).hasSize(1);
        assertThat(secondPage.hasNext()).isFalse();
    }

    @Test
    void 입찰이_없으면_countByAuctionId는_0이다() {
        setUpFixture();

        assertThat(bidRepository.countByAuctionId(auction.getId())).isZero();
    }

    @Test
    void countByAuctionId는_실제_입찰_개수와_같다() {
        setUpFixture();
        entityManager.persist(Bid.place(auction, bidder, 15000L, BidType.MANUAL));
        entityManager.persist(Bid.place(auction, bidder, 20000L, BidType.MANUAL));
        entityManager.flush();
        entityManager.clear();

        assertThat(bidRepository.countByAuctionId(auction.getId())).isEqualTo(2);
    }

    @Test
    void 금액이_0이하인_입찰은_DB_CHECK_제약에_의해서도_저장에_실패한다() {
        setUpFixture();
        entityManager.flush();

        // amount는 도메인 팩토리(Bid.place)에서 이미 걸러지므로, DB CHECK 자체를 직접 검증하려면
        // 팩토리를 우회해야 한다. 네이티브 쿼리로 직접 위반 케이스를 삽입해 확인한다.
        assertThatThrownBy(() -> {
            entityManager.createNativeQuery(
                            "INSERT INTO bids (auction_id, user_id, amount, bid_type, created_at) "
                                    + "VALUES (:auctionId, :userId, 0, 'MANUAL', NOW())"
                    )
                    .setParameter("auctionId", auction.getId())
                    .setParameter("userId", bidder.getId())
                    .executeUpdate();
        }).isInstanceOf(RuntimeException.class);
    }
}
