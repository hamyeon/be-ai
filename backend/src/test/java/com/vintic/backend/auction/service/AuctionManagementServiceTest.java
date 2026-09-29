package com.vintic.backend.auction.service;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.dto.AuctionCancelResponse;
import com.vintic.backend.auction.dto.ChangeStartPriceResponse;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.common.exception.AuctionCancelWindowClosedException;
import com.vintic.backend.common.exception.AuctionNotFoundException;
import com.vintic.backend.common.exception.AuctionSellerMismatchException;
import com.vintic.backend.common.exception.StartPriceChangeWindowClosedException;
import com.vintic.backend.product.domain.Product;
import com.vintic.backend.user.domain.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuctionManagementServiceTest {

    // 2026-08-18 20:00:00 KST 고정.
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-08-18T11:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final LocalDateTime NOW = LocalDateTime.now(FIXED_CLOCK);

    @Mock
    private AuctionRepository auctionRepository;

    private AuctionManagementService sut;

    private final User seller = user(1L);

    private void initSut() {
        sut = new AuctionManagementService(auctionRepository, FIXED_CLOCK);
    }

    private static User user(Long id) {
        User user = User.register("seller-" + id + "@vintic.local", "seller" + id, null);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private Auction auctionStartingAt(LocalDateTime startAt) {
        Product product = new Product(
                seller,
                List.of("https://example.com/a.jpg"),
                "Nike", "Dunk Low", "Panda", 270, "B", "PARTIAL",
                300000, 350000, "285,000원 ~ 315,000원", 290000, "사유", "설명"
        );
        ReflectionTestUtils.setField(product, "id", 10L);
        Auction auction = Auction.schedule(product, 10000L, 5000L, startAt, startAt.plusHours(2));
        ReflectionTestUtils.setField(auction, "id", 100L);
        return auction;
    }

    // ---- cancel: now < startAt ----

    @Test
    void 시작_전이면_취소할_수_있다() {
        initSut();
        Auction auction = auctionStartingAt(NOW.plusMinutes(1));
        when(auctionRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(auction));

        AuctionCancelResponse response = sut.cancel(100L, 1L);

        assertThat(response.status()).isEqualTo("CANCELED");
    }

    @Test
    void 시작_시각과_현재가_정확히_같으면_취소할_수_없다() {
        initSut();
        Auction auction = auctionStartingAt(NOW);
        when(auctionRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(auction));

        assertThatThrownBy(() -> sut.cancel(100L, 1L))
                .isInstanceOf(AuctionCancelWindowClosedException.class);
    }

    @Test
    void 이미_시작된_뒤이면_취소할_수_없다() {
        initSut();
        Auction auction = auctionStartingAt(NOW.minusMinutes(1));
        auction.start();
        when(auctionRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(auction));

        assertThatThrownBy(() -> sut.cancel(100L, 1L))
                .isInstanceOf(AuctionCancelWindowClosedException.class);
    }

    @Test
    void 본인_경매가_아니면_취소할_수_없다() {
        initSut();
        Auction auction = auctionStartingAt(NOW.plusMinutes(1));
        when(auctionRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(auction));

        assertThatThrownBy(() -> sut.cancel(100L, 999L))
                .isInstanceOf(AuctionSellerMismatchException.class);
    }

    @Test
    void 존재하지_않는_경매는_취소할_수_없다() {
        initSut();
        when(auctionRepository.findByIdForUpdate(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sut.cancel(999L, 1L))
                .isInstanceOf(AuctionNotFoundException.class);
    }

    // ---- changeStartPrice: now <= startAt - 1h ----

    @Test
    void 시작_1시간_전이면_시작가를_수정할_수_있다() {
        initSut();
        Auction auction = auctionStartingAt(NOW.plusHours(1));
        when(auctionRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(auction));

        ChangeStartPriceResponse response = sut.changeStartPrice(100L, 1L, 20000L);

        assertThat(response.startPrice()).isEqualTo(20000L);
    }

    @Test
    void 시작_59분59초_전이면_시작가를_수정할_수_없다() {
        initSut();
        Auction auction = auctionStartingAt(NOW.plusMinutes(59).plusSeconds(59));
        when(auctionRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(auction));

        assertThatThrownBy(() -> sut.changeStartPrice(100L, 1L, 20000L))
                .isInstanceOf(StartPriceChangeWindowClosedException.class);
    }

    @Test
    void 본인_경매가_아니면_시작가를_수정할_수_없다() {
        initSut();
        Auction auction = auctionStartingAt(NOW.plusHours(2));
        when(auctionRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(auction));

        assertThatThrownBy(() -> sut.changeStartPrice(100L, 999L, 20000L))
                .isInstanceOf(AuctionSellerMismatchException.class);
    }

    @Test
    void SCHEDULED가_아니면_시작가를_수정할_수_없다() {
        initSut();
        Auction auction = auctionStartingAt(NOW.minusMinutes(1));
        auction.start();
        when(auctionRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(auction));

        assertThatThrownBy(() -> sut.changeStartPrice(100L, 1L, 20000L))
                .isInstanceOf(StartPriceChangeWindowClosedException.class);
    }
}
