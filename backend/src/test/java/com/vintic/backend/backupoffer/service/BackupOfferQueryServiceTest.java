package com.vintic.backend.backupoffer.service;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.backupoffer.domain.BackupOffer;
import com.vintic.backend.backupoffer.dto.BackupOfferResponse;
import com.vintic.backend.backupoffer.repository.BackupOfferRepository;
import com.vintic.backend.common.exception.BackupOfferAccessDeniedException;
import com.vintic.backend.common.exception.BackupOfferNotFoundException;
import com.vintic.backend.product.domain.Product;
import com.vintic.backend.support.TestClockConfig;
import com.vintic.backend.user.domain.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

// FINAL contract §15.
@DataJpaTest
@Import({BackupOfferQueryService.class, TestClockConfig.class})
class BackupOfferQueryServiceTest {

    @Autowired
    private BackupOfferQueryService backupOfferQueryService;

    @Autowired
    private BackupOfferRepository backupOfferRepository;

    @Autowired
    private EntityManager entityManager;

    private User persistUser(String email) {
        User user = User.register(email, email, null);
        entityManager.persist(user);
        return user;
    }

    private Product persistProduct(User seller) {
        Product product = new Product(
                seller,
                List.of("https://example.com/a.jpg"),
                "Nike", "Dunk Low", "Panda", 270, "B", "PARTIAL",
                300000, 350000, "285,000원 ~ 315,000원", 290000, "사유", "설명"
        );
        entityManager.persist(product);
        return product;
    }

    private Auction persistEndedAuction(Product product) {
        Auction auction = Auction.schedule(
                product, 10000L, 5000L, LocalDateTime.now().minusHours(2), LocalDateTime.now().minusHours(1)
        );
        auction.start();
        auction.end();
        entityManager.persist(auction);
        return auction;
    }

    @Test
    void 조회_성공시_purchasePrice_shippingFee_totalAmount_deadline을_반환한다() {
        User seller = persistUser("seller@vintic.local");
        User candidate = persistUser("candidate@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistEndedAuction(product);
        BackupOffer offer = backupOfferRepository.save(BackupOffer.create(auction, candidate, 100000L));
        entityManager.flush();
        entityManager.clear();

        BackupOfferResponse response = backupOfferQueryService.getBackupOffer(offer.getId(), candidate.getId());

        assertThat(response.backupOfferId()).isEqualTo(offer.getId());
        assertThat(response.auctionId()).isEqualTo(auction.getId());
        assertThat(response.status().name()).isEqualTo("WAITING");
        assertThat(response.purchasePrice()).isEqualTo(100000L);
        assertThat(response.shippingFee()).isEqualTo(3000L);
        assertThat(response.totalAmount()).isEqualTo(103000L);
        // deadline = createdAt + 24h(§0.10) - offer는 방금 생성됐으므로 now+24h와 근접해야 한다.
        assertThat(response.deadline().toLocalDateTime())
                .isCloseTo(LocalDateTime.now().plusHours(24), within(5, ChronoUnit.SECONDS));
    }

    @Test
    void 존재하지_않는_backupOffer_조회는_예외가_발생한다() {
        assertThatThrownBy(() -> backupOfferQueryService.getBackupOffer(9999L, 1L))
                .isInstanceOf(BackupOfferNotFoundException.class);
    }

    @Test
    void candidate가_아닌_사용자의_조회는_403_예외가_발생한다() {
        User seller = persistUser("seller2@vintic.local");
        User candidate = persistUser("candidate2@vintic.local");
        User stranger = persistUser("stranger@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistEndedAuction(product);
        BackupOffer offer = backupOfferRepository.save(BackupOffer.create(auction, candidate, 100000L));
        entityManager.flush();
        entityManager.clear();

        assertThatThrownBy(() -> backupOfferQueryService.getBackupOffer(offer.getId(), stranger.getId()))
                .isInstanceOf(BackupOfferAccessDeniedException.class);
    }
}
