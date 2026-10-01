package com.vintic.backend.backupoffer.service;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.backupoffer.domain.BackupOffer;
import com.vintic.backend.backupoffer.dto.BackupOfferResponse;
import com.vintic.backend.backupoffer.repository.BackupOfferRepository;
import com.vintic.backend.common.exception.BackupOfferAccessDeniedException;
import com.vintic.backend.common.exception.BackupOfferNotFoundException;
import com.vintic.backend.common.util.S3UrlPresigner;
import com.vintic.backend.product.domain.Product;
import com.vintic.backend.support.TestClockConfig;
import com.vintic.backend.user.domain.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

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

    // 버킷을 public-read로 열지 않으므로 응답 직전 presign한다(S3Config 참고) - @DataJpaTest
    // 슬라이스는 S3Config를 가져오지 않아 실제 S3Presigner 빈이 없다. presign 자체는 이
    // 테스트의 관심사가 아니라 입력 URL을 그대로 돌려준다(S3UrlPresignerTest가 presign
    // 동작을 검증한다).
    @MockitoBean
    private S3UrlPresigner s3UrlPresigner;

    @BeforeEach
    void setUpPresigner() {
        lenient().when(s3UrlPresigner.presign(anyString(), any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

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
