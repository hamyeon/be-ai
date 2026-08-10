package com.vintic.backend.bid.service;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.bid.domain.Bid;
import com.vintic.backend.bid.domain.BidType;
import com.vintic.backend.bid.dto.BidHistoryResponse;
import com.vintic.backend.common.exception.AuctionNotFoundException;
import com.vintic.backend.product.domain.Product;
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

@DataJpaTest
@Import(BidQueryService.class)
class BidQueryServiceTest {

    @Autowired
    private BidQueryService bidQueryService;

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

    private Bid persistBid(long amount) {
        Bid bid = Bid.place(auction, bidder, amount, BidType.MANUAL);
        entityManager.persist(bid);
        return bid;
    }

    @Test
    void order를_지정하지_않으면_latest_순서다() {
        setUpFixture();
        Bid first = persistBid(15000L);
        Bid second = persistBid(20000L);
        entityManager.flush();
        entityManager.clear();

        BidHistoryResponse response = bidQueryService.getBidHistory(auction.getId(), 0, 20, "latest");

        assertThat(response.bids()).hasSize(2);
        assertThat(response.bids().get(0).bidId()).isEqualTo(second.getId());
        assertThat(response.bids().get(1).bidId()).isEqualTo(first.getId());
    }

    @Test
    void order가_oldest면_오래된순으로_반환한다() {
        setUpFixture();
        Bid first = persistBid(15000L);
        Bid second = persistBid(20000L);
        entityManager.flush();
        entityManager.clear();

        BidHistoryResponse response = bidQueryService.getBidHistory(auction.getId(), 0, 20, "oldest");

        assertThat(response.bids().get(0).bidId()).isEqualTo(first.getId());
        assertThat(response.bids().get(1).bidId()).isEqualTo(second.getId());
    }

    @Test
    void page_size가_적용되고_다음페이지가_있으면_hasNext는_true다() {
        setUpFixture();
        persistBid(15000L);
        persistBid(16000L);
        persistBid(17000L);
        entityManager.flush();
        entityManager.clear();

        BidHistoryResponse firstPage = bidQueryService.getBidHistory(auction.getId(), 0, 2, "latest");

        assertThat(firstPage.bids()).hasSize(2);
        assertThat(firstPage.page()).isZero();
        assertThat(firstPage.size()).isEqualTo(2);
        assertThat(firstPage.hasNext()).isTrue();

        BidHistoryResponse secondPage = bidQueryService.getBidHistory(auction.getId(), 1, 2, "latest");

        assertThat(secondPage.bids()).hasSize(1);
        assertThat(secondPage.hasNext()).isFalse();
    }

    @Test
    void 입찰이_없으면_빈_결과를_반환한다() {
        setUpFixture();
        entityManager.flush();

        BidHistoryResponse response = bidQueryService.getBidHistory(auction.getId(), 0, 20, "latest");

        assertThat(response.bids()).isEmpty();
        assertThat(response.hasNext()).isFalse();
    }

    @Test
    void 존재하지_않는_경매면_예외가_발생한다() {
        assertThatThrownBy(() -> bidQueryService.getBidHistory(999L, 0, 20, "latest"))
                .isInstanceOf(AuctionNotFoundException.class);
    }
}
