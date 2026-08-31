package com.vintic.backend.bid.service;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.bid.domain.Bid;
import com.vintic.backend.bid.domain.BidType;
import com.vintic.backend.bid.dto.BidHistoryResponse;
import com.vintic.backend.common.exception.AuctionNotFoundException;
import com.vintic.backend.product.domain.Product;
import com.vintic.backend.user.domain.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;

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

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private User seller;
    private User bidder;
    private Auction auction;

    private void setUpFixture() {
        seller = User.register("seller@vintic.local", "seller", null);
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

    private Bid persistBid(User user, long amount) {
        Bid bid = Bid.place(auction, user, amount, BidType.MANUAL);
        entityManager.persist(bid);
        return bid;
    }

    @Test
    void order를_지정하지_않으면_latest_순서다() {
        setUpFixture();
        Bid first = persistBid(bidder, 15000L);
        Bid second = persistBid(bidder, 20000L);
        entityManager.flush();
        entityManager.clear();

        BidHistoryResponse response = bidQueryService.getBidHistory(auction.getId(), null, 0, 20, "latest");

        assertThat(response.bids()).hasSize(2);
        assertThat(response.bids().get(0).bidId()).isEqualTo(second.getId());
        assertThat(response.bids().get(1).bidId()).isEqualTo(first.getId());
    }

    @Test
    void order가_oldest면_오래된순으로_반환한다() {
        setUpFixture();
        Bid first = persistBid(bidder, 15000L);
        Bid second = persistBid(bidder, 20000L);
        entityManager.flush();
        entityManager.clear();

        BidHistoryResponse response = bidQueryService.getBidHistory(auction.getId(), null, 0, 20, "oldest");

        assertThat(response.bids().get(0).bidId()).isEqualTo(first.getId());
        assertThat(response.bids().get(1).bidId()).isEqualTo(second.getId());
    }

    @Test
    void page_size가_적용되고_다음페이지가_있으면_hasNext는_true다() {
        setUpFixture();
        persistBid(bidder, 15000L);
        persistBid(bidder, 16000L);
        persistBid(bidder, 17000L);
        entityManager.flush();
        entityManager.clear();

        BidHistoryResponse firstPage = bidQueryService.getBidHistory(auction.getId(), null, 0, 2, "latest");

        assertThat(firstPage.bids()).hasSize(2);
        assertThat(firstPage.page()).isZero();
        assertThat(firstPage.size()).isEqualTo(2);
        assertThat(firstPage.hasNext()).isTrue();

        BidHistoryResponse secondPage = bidQueryService.getBidHistory(auction.getId(), null, 1, 2, "latest");

        assertThat(secondPage.bids()).hasSize(1);
        assertThat(secondPage.hasNext()).isFalse();
    }

    @Test
    void 입찰이_없으면_빈_결과를_반환한다() {
        setUpFixture();
        entityManager.flush();

        BidHistoryResponse response = bidQueryService.getBidHistory(auction.getId(), null, 0, 20, "latest");

        assertThat(response.bids()).isEmpty();
        assertThat(response.hasNext()).isFalse();
    }

    @Test
    void 존재하지_않는_경매면_예외가_발생한다() {
        assertThatThrownBy(() -> bidQueryService.getBidHistory(999L, null, 0, 20, "latest"))
                .isInstanceOf(AuctionNotFoundException.class);
    }

    // ===== #55 FINAL contract: masking/isMine/isHighest/bidType =====

    @Test
    void bidderMasked는_NicknameMasker_규칙으로_마스킹된다() {
        setUpFixture();
        persistBid(bidder, 15000L);
        entityManager.flush();
        entityManager.clear();

        BidHistoryResponse response = bidQueryService.getBidHistory(auction.getId(), null, 0, 20, "latest");

        assertThat(response.bids().get(0).bidderMasked()).isEqualTo("bid****");
    }

    @Test
    void viewerUserId가_입찰자와_같으면_isMine은_true다() {
        setUpFixture();
        persistBid(bidder, 15000L);
        entityManager.flush();
        entityManager.clear();

        BidHistoryResponse response = bidQueryService.getBidHistory(auction.getId(), bidder.getId(), 0, 20, "latest");

        assertThat(response.bids().get(0).isMine()).isTrue();
    }

    @Test
    void viewerUserId가_없으면_모든_항목이_isMine_false다() {
        setUpFixture();
        persistBid(bidder, 15000L);
        entityManager.flush();
        entityManager.clear();

        BidHistoryResponse response = bidQueryService.getBidHistory(auction.getId(), null, 0, 20, "latest");

        assertThat(response.bids().get(0).isMine()).isFalse();
    }

    @Test
    void viewerUserId가_다른_사용자면_isMine은_false다() {
        setUpFixture();
        User other = User.register("other@vintic.local", "other", null);
        entityManager.persist(other);
        persistBid(bidder, 15000L);
        entityManager.flush();
        entityManager.clear();

        BidHistoryResponse response = bidQueryService.getBidHistory(auction.getId(), other.getId(), 0, 20, "latest");

        assertThat(response.bids().get(0).isMine()).isFalse();
    }

    @Test
    void 현재_winner의_currentPrice와_일치하는_단_하나의_Bid만_isHighest_true다() {
        setUpFixture();
        persistBid(bidder, 15000L);
        persistBid(bidder, 20000L);
        // Bid row 자체와 별개로 Auction의 실제 winner/currentPrice를 20000으로 맞춘다 -
        // isHighest가 "목록의 마지막 항목"이 아니라 이 값을 기준으로 계산됨을 검증하기 위함이다.
        // placeManualBid()는 "이미 최고입찰자인 사용자의 재입찰"을 막으므로(같은 bidder가
        // 15000 -> 20000으로 스스로를 이기는 시나리오는 도메인상 불가능) 필드를 직접 설정한다.
        ReflectionTestUtils.setField(auction, "currentPrice", 20000L);
        ReflectionTestUtils.setField(auction, "currentWinner", bidder);
        entityManager.flush();
        entityManager.clear();

        BidHistoryResponse response = bidQueryService.getBidHistory(auction.getId(), null, 0, 20, "oldest");

        assertThat(response.bids()).hasSize(2);
        assertThat(response.bids().get(0).amount()).isEqualTo(15000L);
        assertThat(response.bids().get(0).isHighest()).isFalse();
        assertThat(response.bids().get(1).amount()).isEqualTo(20000L);
        assertThat(response.bids().get(1).isHighest()).isTrue();
    }

    @Test
    void winner가_없으면_모든_Bid의_isHighest는_false다() {
        setUpFixture();
        // Bid만 직접 심어 currentWinner는 비워둔 상태(도메인상 비정상이지만 isHighest 계산이
        // "목록 위치/최댓값 추정"이 아니라 실제 currentWinner 기준임을 확인하는 경계 케이스다).
        persistBid(bidder, 15000L);
        entityManager.flush();
        entityManager.clear();

        BidHistoryResponse response = bidQueryService.getBidHistory(auction.getId(), null, 0, 20, "latest");

        assertThat(response.bids().get(0).isHighest()).isFalse();
    }

    @Test
    void bidType을_그대로_반환한다() {
        setUpFixture();
        entityManager.persist(Bid.place(auction, bidder, 15000L, BidType.AUTO));
        entityManager.flush();
        entityManager.clear();

        BidHistoryResponse response = bidQueryService.getBidHistory(auction.getId(), null, 0, 20, "latest");

        assertThat(response.bids().get(0).bidType()).isEqualTo(BidType.AUTO);
    }

    // ===== #55 N+1 audit =====

    @Test
    void 입찰자가_여러명이어도_bidderMasked_계산으로_추가_SELECT가_늘어나지_않는다() {
        setUpFixture();
        User bidder2 = User.register("bidder2@vintic.local", "bidder2", null);
        entityManager.persist(bidder2);
        User bidder3 = User.register("bidder3@vintic.local", "bidder3", null);
        entityManager.persist(bidder3);
        persistBid(bidder, 15000L);
        persistBid(bidder2, 16000L);
        persistBid(bidder3, 17000L);
        entityManager.flush();
        entityManager.clear();

        SessionFactory sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
        Statistics statistics = sessionFactory.getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();

        BidHistoryResponse response = bidQueryService.getBidHistory(auction.getId(), null, 0, 20, "latest");
        // 지연 로딩된 필드를 실제로 건드려야 N+1이 있다면 여기서 터진다.
        response.bids().forEach(bid -> assertThat(bid.bidderMasked()).isNotNull());

        long queryCount = statistics.getPrepareStatementCount();
        // Auction 존재 확인(1) + Bid 페이지 조회(1, join fetch로 user 포함) + count 쿼리(1) = 3.
        // bidder 3명이든 30명이든 이 값은 늘어나지 않아야 한다(N+1이면 3 + N으로 늘어난다).
        assertThat(queryCount)
                .as("bidderMasked 계산이 입찰자 수만큼 추가 SELECT를 내면 안 된다(N+1)")
                .isLessThanOrEqualTo(3);
    }
}
