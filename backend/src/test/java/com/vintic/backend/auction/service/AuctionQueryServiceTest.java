package com.vintic.backend.auction.service;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.dto.AuctionDetailResponse;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.bid.domain.Bid;
import com.vintic.backend.bid.domain.BidType;
import com.vintic.backend.bid.repository.BidRepository;
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

// @DataJpaTest 슬라이스에 서비스를 직접 Import해, sellerId/bidCount가 실제 저장된
// Product.seller / Bid 개수와 일치하는지 실제 DB 조회로 검증한다.
@DataJpaTest
@Import(AuctionQueryService.class)
class AuctionQueryServiceTest {

    @Autowired
    private AuctionQueryService auctionQueryService;

    @Autowired
    private AuctionRepository auctionRepository;

    @Autowired
    private BidRepository bidRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void sellerId는_Product_seller_id와_같다() {
        User seller = User.register("seller@vintic.local", "seller", null);
        entityManager.persist(seller);
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
        entityManager.flush();
        entityManager.clear();

        AuctionDetailResponse response = auctionQueryService.getAuctionDetail(auction.getId());

        assertThat(response.sellerId()).isEqualTo(seller.getId());
    }

    @Test
    void bidCount는_실제_저장된_입찰_개수와_같다() {
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
        entityManager.persist(Bid.place(auction, bidder, 15000L, BidType.MANUAL));
        entityManager.persist(Bid.place(auction, bidder, 20000L, BidType.MANUAL));
        entityManager.flush();
        entityManager.clear();

        AuctionDetailResponse response = auctionQueryService.getAuctionDetail(auction.getId());

        assertThat(response.bidCount()).isEqualTo(2L);
    }

    @Test
    void 입찰이_없으면_bidCount는_0이다() {
        User seller = User.register("seller@vintic.local", "seller", null);
        entityManager.persist(seller);
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
        entityManager.flush();
        entityManager.clear();

        AuctionDetailResponse response = auctionQueryService.getAuctionDetail(auction.getId());

        assertThat(response.bidCount()).isZero();
    }

    @Test
    void 존재하지_않는_경매를_조회하면_예외가_발생한다() {
        assertThatThrownBy(() -> auctionQueryService.getAuctionDetail(999L))
                .isInstanceOf(AuctionNotFoundException.class);
    }
}
