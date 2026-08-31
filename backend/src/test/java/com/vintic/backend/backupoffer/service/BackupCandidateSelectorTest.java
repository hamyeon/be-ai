package com.vintic.backend.backupoffer.service;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.bid.domain.Bid;
import com.vintic.backend.bid.domain.BidType;
import com.vintic.backend.product.domain.Product;
import com.vintic.backend.user.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class BackupCandidateSelectorTest {

    // User는 순수 도메인 객체(영속화 전)라 id가 null이다 - rankOf()가 User.getId()로 비교하므로
    // 이 테스트에서는 직접 id를 채워준다(AuctionTest의 ReflectionTestUtils 사용과 동일 패턴).
    private final AtomicLong nextUserId = new AtomicLong(1);

    private final User seller = User.register("seller@vintic.local", "seller", null);
    private final Product product = new Product(
            seller,
            List.of("https://example.com/a.jpg"),
            "Nike", "Dunk Low", "Panda", 270, "B", "PARTIAL",
            300000, 350000, "285,000원 ~ 315,000원", 290000, "사유", "설명"
    );
    private final Auction auction = Auction.schedule(
            product, 10000L, 5000L, LocalDateTime.now().minusHours(2), LocalDateTime.now().minusHours(1)
    );

    private Bid bidFor(String email, long amount) {
        User bidder = User.register(email, email, null);
        ReflectionTestUtils.setField(bidder, "id", nextUserId.getAndIncrement());
        return Bid.place(auction, bidder, amount, BidType.MANUAL);
    }

    @Test
    void rank1_다음은_rank2다() {
        List<Bid> ranked = List.of(bidFor("a@vintic.local", 30000L), bidFor("b@vintic.local", 20000L));

        Optional<Bid> next = BackupCandidateSelector.next(ranked, 1);

        assertThat(next).isPresent();
        assertThat(next.get().getAmount()).isEqualTo(20000L);
    }

    @Test
    void rank2_다음은_rank3다() {
        List<Bid> ranked = List.of(
                bidFor("a@vintic.local", 30000L), bidFor("b@vintic.local", 20000L), bidFor("c@vintic.local", 10000L)
        );

        Optional<Bid> next = BackupCandidateSelector.next(ranked, 2);

        assertThat(next).isPresent();
        assertThat(next.get().getAmount()).isEqualTo(10000L);
    }

    @Test
    void rank3_다음은_후보가_없다() {
        List<Bid> ranked = List.of(
                bidFor("a@vintic.local", 30000L), bidFor("b@vintic.local", 20000L), bidFor("c@vintic.local", 10000L)
        );

        Optional<Bid> next = BackupCandidateSelector.next(ranked, 3);

        assertThat(next).isEmpty();
    }

    @Test
    void 입찰자_자체가_부족하면_후보가_없다() {
        List<Bid> ranked = List.of(bidFor("a@vintic.local", 30000L));

        assertThat(BackupCandidateSelector.next(ranked, 1)).isEmpty();
    }

    @Test
    void rankOf는_해당_사용자의_1based_순위를_반환한다() {
        Bid first = bidFor("a@vintic.local", 30000L);
        Bid second = bidFor("b@vintic.local", 20000L);
        List<Bid> ranked = List.of(first, second);

        assertThat(BackupCandidateSelector.rankOf(ranked, second.getUser().getId())).contains(2);
    }

    @Test
    void rankOf는_목록에_없는_사용자면_빈_값을_반환한다() {
        List<Bid> ranked = List.of(bidFor("a@vintic.local", 30000L));

        assertThat(BackupCandidateSelector.rankOf(ranked, 9999L)).isEmpty();
    }
}
