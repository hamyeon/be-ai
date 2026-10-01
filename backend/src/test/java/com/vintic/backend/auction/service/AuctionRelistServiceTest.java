package com.vintic.backend.auction.service;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.dto.AuctionRelistResponse;
import com.vintic.backend.auction.dto.RelistAuctionRequest;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.bid.repository.BidRepository;
import com.vintic.backend.common.exception.ActiveAuctionAlreadyExistsException;
import com.vintic.backend.common.exception.AuctionNotEligibleForReregistrationException;
import com.vintic.backend.common.exception.AuctionNotFoundException;
import com.vintic.backend.common.exception.AuctionRegistrationLimitExceededException;
import com.vintic.backend.common.exception.AuctionSellerMismatchException;
import com.vintic.backend.common.exception.InvalidAuctionTimeException;
import com.vintic.backend.common.util.BidIncrementPolicy;
import com.vintic.backend.product.domain.Product;
import com.vintic.backend.product.repository.ProductRepository;
import com.vintic.backend.user.domain.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

// 첫 경매는 POST /api/products(ProductRegistrationService)로만 만들어지므로, 이 서비스가
// 다루는 previousAuctionId는 항상 "이미 존재하는 이전 경매"다 - productId 기반 등록 경로는
// 더 이상 존재하지 않는다(AuctionRegistrationService/POST /api/products/{id}/auctions 제거됨).
@ExtendWith(MockitoExtension.class)
class AuctionRelistServiceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-08-18T11:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final OffsetDateTime NOW_KST = OffsetDateTime.now(FIXED_CLOCK);

    @Mock
    private ProductRepository productRepository;

    @Mock
    private AuctionRepository auctionRepository;

    @Mock
    private BidRepository bidRepository;

    private AuctionRelistService sut;

    private final User seller = user(1L);
    private final Product product = product(seller, 10L);

    private void initSut() {
        sut = new AuctionRelistService(productRepository, auctionRepository, bidRepository, FIXED_CLOCK);
    }

    private static User user(Long id) {
        User user = User.register("seller-" + id + "@vintic.local", "seller" + id, null);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private static Product product(User seller, Long id) {
        Product product = new Product(
                seller,
                List.of("https://example.com/a.jpg"),
                "Nike", "Dunk Low", "Panda", 270, "B", "PARTIAL",
                300000, 350000, "285,000원 ~ 315,000원", 290000, "사유", "설명"
        );
        ReflectionTestUtils.setField(product, "id", id);
        return product;
    }

    private static Auction priorAuction(Product product, Long id, Consumer<Auction> transitions) {
        Auction auction = Auction.schedule(
                product, 10000L, 5000L,
                LocalDateTime.now().minusDays(2), LocalDateTime.now().minusDays(1).plusHours(2)
        );
        ReflectionTestUtils.setField(auction, "id", id);
        transitions.accept(auction);
        return auction;
    }

    private RelistAuctionRequest requestWith(OffsetDateTime startAt, OffsetDateTime endAt) {
        return new RelistAuctionRequest(10000L, startAt, endAt);
    }

    @Test
    void 시작전_취소된_이전_경매_뒤에는_재경매할_수_있다() {
        initSut();
        Auction previous = priorAuction(product, 100L, Auction::cancel);
        when(auctionRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(previous));
        when(productRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(product));
        when(auctionRepository.findAllByProductId(10L)).thenReturn(List.of(previous));
        when(auctionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        AuctionRelistResponse response = sut.relist(100L, 1L, requestWith(NOW_KST.plusHours(2), NOW_KST.plusHours(3)));

        assertThat(response.status()).isEqualTo("SCHEDULED");
        assertThat(response.previousAuctionId()).isEqualTo(100L);
        assertThat(response.bidIncrement()).isEqualTo(BidIncrementPolicy.DEFAULT_BID_INCREMENT);
    }

    @Test
    void 입찰없이_종료된_유찰_이전_경매_뒤에는_재경매할_수_있다() {
        initSut();
        Auction previous = priorAuction(product, 100L, a -> {
            a.start();
            a.end();
        });
        when(auctionRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(previous));
        when(productRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(product));
        when(auctionRepository.findAllByProductId(10L)).thenReturn(List.of(previous));
        when(bidRepository.countByAuctionId(100L)).thenReturn(0L);
        when(auctionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        AuctionRelistResponse response = sut.relist(100L, 1L, requestWith(NOW_KST.plusHours(2), NOW_KST.plusHours(3)));

        assertThat(response.status()).isEqualTo("SCHEDULED");
    }

    @Test
    void 존재하지_않는_이전_경매면_실패한다() {
        initSut();
        when(auctionRepository.findByIdForUpdate(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sut.relist(999L, 1L, requestWith(NOW_KST.plusHours(2), NOW_KST.plusHours(3))))
                .isInstanceOf(AuctionNotFoundException.class);
    }

    @Test
    void 본인_상품이_아니면_실패한다() {
        initSut();
        Auction previous = priorAuction(product, 100L, Auction::cancel);
        when(auctionRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(previous));
        when(productRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(product));

        assertThatThrownBy(() -> sut.relist(100L, 999L, requestWith(NOW_KST.plusHours(2), NOW_KST.plusHours(3))))
                .isInstanceOf(AuctionSellerMismatchException.class);
    }

    @Test
    void 이전_경매가_아직_끝나지_않았으면_재경매할_수_없다() {
        initSut();
        Auction previous = priorAuction(product, 100L, a -> { });
        when(auctionRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(previous));
        when(productRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(product));

        assertThatThrownBy(() -> sut.relist(100L, 1L, requestWith(NOW_KST.plusHours(2), NOW_KST.plusHours(3))))
                .isInstanceOf(AuctionNotEligibleForReregistrationException.class);
    }

    @Test
    void 이전_경매에_입찰이_있었으면_재경매할_수_없다() {
        initSut();
        Auction previous = priorAuction(product, 100L, a -> {
            a.start();
            a.end();
        });
        when(auctionRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(previous));
        when(productRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(product));
        when(bidRepository.countByAuctionId(100L)).thenReturn(1L);

        assertThatThrownBy(() -> sut.relist(100L, 1L, requestWith(NOW_KST.plusHours(2), NOW_KST.plusHours(3))))
                .isInstanceOf(AuctionNotEligibleForReregistrationException.class);
    }

    @Test
    void 다른_활성_경매가_이미_있으면_재경매할_수_없다() {
        initSut();
        Auction previous = priorAuction(product, 100L, Auction::cancel);
        Auction active = priorAuction(product, 101L, a -> { });
        when(auctionRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(previous));
        when(productRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(product));
        when(auctionRepository.findAllByProductId(10L)).thenReturn(List.of(previous, active));

        assertThatThrownBy(() -> sut.relist(100L, 1L, requestWith(NOW_KST.plusHours(2), NOW_KST.plusHours(3))))
                .isInstanceOf(ActiveAuctionAlreadyExistsException.class);
    }

    @Test
    void 총_2회를_모두_사용했으면_재경매를_거절한다() {
        initSut();
        Auction first = priorAuction(product, 100L, a -> {
            a.start();
            a.end();
        });
        Auction second = priorAuction(product, 101L, Auction::cancel);
        when(auctionRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(first));
        when(productRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(product));
        when(auctionRepository.findAllByProductId(10L)).thenReturn(List.of(first, second));
        when(bidRepository.countByAuctionId(100L)).thenReturn(0L);

        assertThatThrownBy(() -> sut.relist(100L, 1L, requestWith(NOW_KST.plusHours(2), NOW_KST.plusHours(3))))
                .isInstanceOf(AuctionRegistrationLimitExceededException.class);
    }

    @Test
    void 시작시각이_현재보다_미래가_아니면_실패한다() {
        initSut();
        Auction previous = priorAuction(product, 100L, Auction::cancel);
        when(auctionRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(previous));
        when(productRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(product));

        assertThatThrownBy(() -> sut.relist(100L, 1L, requestWith(NOW_KST, NOW_KST.plusHours(1))))
                .isInstanceOf(InvalidAuctionTimeException.class);
    }

    @Test
    void 진행시간이_59분59초이면_거절된다() {
        initSut();
        Auction previous = priorAuction(product, 100L, Auction::cancel);
        when(auctionRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(previous));
        when(productRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(product));

        OffsetDateTime startAt = NOW_KST.plusHours(1);
        OffsetDateTime endAt = startAt.plusMinutes(59).plusSeconds(59);

        assertThatThrownBy(() -> sut.relist(100L, 1L, requestWith(startAt, endAt)))
                .isInstanceOf(InvalidAuctionTimeException.class);
    }

    @Test
    void 진행시간이_정확히_1시간이면_허용된다() {
        initSut();
        Auction previous = priorAuction(product, 100L, Auction::cancel);
        when(auctionRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(previous));
        when(productRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(product));
        when(auctionRepository.findAllByProductId(10L)).thenReturn(List.of(previous));
        when(auctionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        OffsetDateTime startAt = NOW_KST.plusHours(1);
        OffsetDateTime endAt = startAt.plusHours(1);

        AuctionRelistResponse response = sut.relist(100L, 1L, requestWith(startAt, endAt));

        assertThat(response.status()).isEqualTo("SCHEDULED");
    }

    @Test
    void 서로_다른_UTC_오프셋으로_입력해도_절대_시각_기준으로_1시간_미만이면_거절된다() {
        initSut();
        Auction previous = priorAuction(product, 100L, Auction::cancel);
        when(auctionRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(previous));
        when(productRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(product));

        OffsetDateTime startAt = NOW_KST.plusHours(2).withOffsetSameInstant(ZoneOffset.of("+09:00"));
        OffsetDateTime endAt = startAt.plusMinutes(59).withOffsetSameInstant(ZoneOffset.UTC);

        assertThatThrownBy(() -> sut.relist(100L, 1L, requestWith(startAt, endAt)))
                .isInstanceOf(InvalidAuctionTimeException.class);
    }
}
