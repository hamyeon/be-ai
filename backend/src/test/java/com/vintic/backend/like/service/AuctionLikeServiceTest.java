package com.vintic.backend.like.service;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.common.exception.AuctionNotFoundException;
import com.vintic.backend.like.domain.AuctionLike;
import com.vintic.backend.like.dto.LikeResponse;
import com.vintic.backend.like.repository.AuctionLikeRepository;
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

// FINAL contract §19/§20은 "이미 좋아요한 상태에서 POST" / "좋아요가 없는 상태에서 DELETE"에
// 대한 별도 에러를 정의하지 않는다 - 두 케이스 모두 멱등(재요청해도 에러 없이 현재 상태 반환)
// 하다는 것을 이 테스트로 고정한다.
@DataJpaTest
@Import({AuctionLikeService.class, AuctionLikeCommandService.class})
class AuctionLikeServiceTest {

    @Autowired
    private AuctionLikeService auctionLikeService;

    @Autowired
    private AuctionLikeRepository auctionLikeRepository;

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
    void 처음_좋아요하면_liked_true와_likeCount_1을_반환한다() {
        User seller = persistUser("seller@vintic.local");
        User liker = persistUser("liker@vintic.local");
        Auction auction = persistAuction(seller);
        flushAndClear();

        LikeResponse response = auctionLikeService.like(auction.getId(), liker.getId());

        assertThat(response.liked()).isTrue();
        assertThat(response.likeCount()).isEqualTo(1);
        assertThat(auctionLikeRepository.existsByAuctionIdAndUserId(auction.getId(), liker.getId())).isTrue();
    }

    @Test
    void 이미_좋아요한_상태에서_다시_좋아요해도_중복_row가_생기지_않고_liked_true를_반환한다() {
        User seller = persistUser("seller@vintic.local");
        User liker = persistUser("liker@vintic.local");
        Auction auction = persistAuction(seller);
        flushAndClear();

        auctionLikeService.like(auction.getId(), liker.getId());
        LikeResponse response = auctionLikeService.like(auction.getId(), liker.getId());

        assertThat(response.liked()).isTrue();
        assertThat(response.likeCount()).isEqualTo(1);
    }

    @Test
    void likeCount는_서로_다른_사용자의_좋아요_수를_정확히_반영한다() {
        User seller = persistUser("seller@vintic.local");
        User likerA = persistUser("liker-a@vintic.local");
        User likerB = persistUser("liker-b@vintic.local");
        Auction auction = persistAuction(seller);
        flushAndClear();

        auctionLikeService.like(auction.getId(), likerA.getId());
        LikeResponse response = auctionLikeService.like(auction.getId(), likerB.getId());

        assertThat(response.likeCount()).isEqualTo(2);
    }

    @Test
    void 존재하지_않는_경매에_좋아요하면_예외가_발생한다() {
        User liker = persistUser("liker@vintic.local");

        assertThatThrownBy(() -> auctionLikeService.like(999L, liker.getId()))
                .isInstanceOf(AuctionNotFoundException.class);
    }

    @Test
    void 좋아요를_해제하면_liked_false와_감소한_likeCount를_반환한다() {
        User seller = persistUser("seller@vintic.local");
        User liker = persistUser("liker@vintic.local");
        Auction auction = persistAuction(seller);
        entityManager.persist(AuctionLike.create(auction, liker));
        flushAndClear();

        LikeResponse response = auctionLikeService.unlike(auction.getId(), liker.getId());

        assertThat(response.liked()).isFalse();
        assertThat(response.likeCount()).isZero();
        assertThat(auctionLikeRepository.existsByAuctionIdAndUserId(auction.getId(), liker.getId())).isFalse();
    }

    @Test
    void 좋아요한_적_없는_사용자가_해제해도_에러_없이_liked_false를_반환한다() {
        User seller = persistUser("seller@vintic.local");
        User viewer = persistUser("viewer@vintic.local");
        Auction auction = persistAuction(seller);
        flushAndClear();

        LikeResponse response = auctionLikeService.unlike(auction.getId(), viewer.getId());

        assertThat(response.liked()).isFalse();
        assertThat(response.likeCount()).isZero();
    }

    @Test
    void 존재하지_않는_경매의_좋아요를_해제하면_예외가_발생한다() {
        User viewer = persistUser("viewer@vintic.local");

        assertThatThrownBy(() -> auctionLikeService.unlike(999L, viewer.getId()))
                .isInstanceOf(AuctionNotFoundException.class);
    }

    @Test
    void 해제해도_다른_사용자의_좋아요는_남는다() {
        User seller = persistUser("seller@vintic.local");
        User likerA = persistUser("liker-a@vintic.local");
        User likerB = persistUser("liker-b@vintic.local");
        Auction auction = persistAuction(seller);
        entityManager.persist(AuctionLike.create(auction, likerA));
        entityManager.persist(AuctionLike.create(auction, likerB));
        flushAndClear();

        LikeResponse response = auctionLikeService.unlike(auction.getId(), likerA.getId());

        assertThat(response.likeCount()).isEqualTo(1);
        assertThat(auctionLikeRepository.existsByAuctionIdAndUserId(auction.getId(), likerB.getId())).isTrue();
    }
}
