package com.vintic.backend.backupoffer.domain;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.common.exception.InvalidBackupOfferStatusException;
import com.vintic.backend.product.domain.Product;
import com.vintic.backend.user.domain.User;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BackupOfferTest {

    private final User seller = User.register("seller@vintic.local", "seller", null);
    private final User candidate = User.register("candidate@vintic.local", "candidate", null);
    private final Product product = new Product(
            seller,
            List.of("https://example.com/a.jpg"),
            "Nike", "Dunk Low", "Panda", 270, "B", "PARTIAL",
            300000, 350000, "285,000원 ~ 315,000원", 290000, "사유", "설명"
    );
    private final Auction auction = Auction.schedule(
            product, 10000L, 5000L, LocalDateTime.now().minusHours(2), LocalDateTime.now().minusHours(1)
    );

    @Test
    void 생성시_기한은_생성시각_플러스_24시간이다() {
        BackupOffer offer = BackupOffer.create(auction, candidate, 20000L);

        assertThat(offer.getDeadline()).isEqualTo(offer.getCreatedAt().plusHours(24));
    }

    @Test
    void WAITING_제안은_만료하면_EXPIRED가_된다() {
        BackupOffer offer = BackupOffer.create(auction, candidate, 20000L);

        offer.expire();

        assertThat(offer.getStatus()).isEqualTo(BackupOfferStatus.EXPIRED);
    }

    @Test
    void 이미_EXPIRED된_제안을_다시_만료하면_예외가_발생한다() {
        BackupOffer offer = BackupOffer.create(auction, candidate, 20000L);
        offer.expire();

        assertThatThrownBy(offer::expire).isInstanceOf(InvalidBackupOfferStatusException.class);
    }

    @Test
    void ACCEPTED된_제안을_만료하면_예외가_발생한다() {
        BackupOffer offer = BackupOffer.create(auction, candidate, 20000L);
        offer.accept();

        assertThatThrownBy(offer::expire).isInstanceOf(InvalidBackupOfferStatusException.class);
    }

    @Test
    void DECLINED된_제안을_만료하면_예외가_발생한다() {
        BackupOffer offer = BackupOffer.create(auction, candidate, 20000L);
        offer.decline();

        assertThatThrownBy(offer::expire).isInstanceOf(InvalidBackupOfferStatusException.class);
    }

    @Test
    void deadline_이전에는_만료로_판정되지_않는다() {
        BackupOffer offer = BackupOffer.create(auction, candidate, 20000L);

        assertThat(offer.isExpired(offer.getDeadline().minusMinutes(1))).isFalse();
    }

    @Test
    void deadline_이후에는_만료로_판정된다() {
        BackupOffer offer = BackupOffer.create(auction, candidate, 20000L);

        assertThat(offer.isExpired(offer.getDeadline().plusMinutes(1))).isTrue();
    }
}
