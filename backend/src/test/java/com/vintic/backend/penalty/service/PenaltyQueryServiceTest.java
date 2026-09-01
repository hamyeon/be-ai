package com.vintic.backend.penalty.service;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.common.exception.UserNotFoundException;
import com.vintic.backend.config.ClockConfig;
import com.vintic.backend.penalty.domain.Penalty;
import com.vintic.backend.penalty.domain.PenaltyType;
import com.vintic.backend.penalty.dto.MyPenaltyResponse;
import com.vintic.backend.penalty.repository.PenaltyRepository;
import com.vintic.backend.product.domain.Product;
import com.vintic.backend.support.TestClockConfig;
import com.vintic.backend.user.domain.User;
import com.vintic.backend.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// FINAL contract §14.
@DataJpaTest
@Import({PenaltyQueryService.class, TestClockConfig.class})
class PenaltyQueryServiceTest {

    private static final LocalDateTime FIXED_NOW = LocalDateTime.ofInstant(TestClockConfig.FIXED_INSTANT, ClockConfig.APP_ZONE);

    @Autowired
    private PenaltyQueryService penaltyQueryService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PenaltyRepository penaltyRepository;

    @Autowired
    private EntityManager entityManager;

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

    private Auction persistAuction(Product product) {
        Auction auction = Auction.schedule(
                product, 10000L, 5000L, LocalDateTime.now().minusHours(2), LocalDateTime.now().minusHours(1)
        );
        entityManager.persist(auction);
        return auction;
    }

    @Test
    void noShowCount는_PAYMENT_EXPIRED_penalty만_반영하고_FORFEITED는_반영하지_않는다() {
        User seller = userRepository.save(User.register("seller@vintic.local", "seller", null));
        User user = userRepository.save(User.register("user@vintic.local", "user", null));
        Product product = persistProduct(seller);
        Auction auction = persistAuction(product);
        // FORFEITED는 User.recordPaymentExpiredPenalty()를 호출하지 않는다 - noShowCount를
        // 올리지 않는다는 사용자 확정 정책을 그대로 반영한다.
        penaltyRepository.save(Penalty.forfeited(user, auction));

        MyPenaltyResponse response = penaltyQueryService.getMyPenalties(user.getId());

        assertThat(response.noShowCount()).isEqualTo(0);
        assertThat(response.penalties()).hasSize(1);
        assertThat(response.penalties().get(0).type()).isEqualTo(PenaltyType.FORFEITED);
    }

    @Test
    void PAYMENT_EXPIRED_penalty가_있으면_noShowCount와_bidRestrictedUntil이_반영된다() {
        User seller = userRepository.save(User.register("seller@vintic.local", "seller", null));
        User user = userRepository.save(User.register("user@vintic.local", "user", null));
        Product product = persistProduct(seller);
        Auction auction = persistAuction(product);
        penaltyRepository.save(Penalty.paymentExpired(user, auction));
        user.recordPaymentExpiredPenalty(FIXED_NOW.plusDays(7));
        userRepository.save(user);

        MyPenaltyResponse response = penaltyQueryService.getMyPenalties(user.getId());

        assertThat(response.noShowCount()).isEqualTo(1);
        assertThat(response.bidRestricted()).isTrue();
        assertThat(response.bidRestrictedUntil().toLocalDateTime()).isEqualTo(FIXED_NOW.plusDays(7));
    }

    @Test
    void bidRestrictedUntil이_지났으면_bidRestricted는_false다() {
        User seller = userRepository.save(User.register("seller@vintic.local", "seller", null));
        User user = userRepository.save(User.register("user@vintic.local", "user", null));
        user.recordPaymentExpiredPenalty(FIXED_NOW.minusDays(1));
        userRepository.save(user);

        MyPenaltyResponse response = penaltyQueryService.getMyPenalties(user.getId());

        assertThat(response.bidRestricted()).isFalse();
    }

    @Test
    void penalties_이력은_FORFEITED와_PAYMENT_EXPIRED를_모두_포함한다() {
        User seller = userRepository.save(User.register("seller@vintic.local", "seller", null));
        User user = userRepository.save(User.register("user@vintic.local", "user", null));
        Product product = persistProduct(seller);
        Auction auction1 = persistAuction(product);
        Auction auction2 = persistAuction(product);
        penaltyRepository.save(Penalty.forfeited(user, auction1));
        penaltyRepository.save(Penalty.paymentExpired(user, auction2));

        MyPenaltyResponse response = penaltyQueryService.getMyPenalties(user.getId());

        assertThat(response.penalties()).hasSize(2);
        assertThat(response.penalties())
                .extracting(MyPenaltyResponse.PenaltyItem::type)
                .containsExactlyInAnyOrder(PenaltyType.FORFEITED, PenaltyType.PAYMENT_EXPIRED);
    }

    @Test
    void penalties_이력_조회는_findByUser_IdOrderByCreatedAtDesc를_사용한다() {
        // #57-2: createdAt은 Penalty에서 updatable=false라 테스트에서 정렬 순서를 강제로 조작할
        // 수 없다(Penalty.java 참고) - Spring Data가 생성한 정렬 조건(OrderByCreatedAtDesc) 자체가
        // 올바른 필드명으로 컴파일/실행되는지만 확인한다(오타나 필드명 변경 시 즉시 깨진다).
        User seller = userRepository.save(User.register("seller@vintic.local", "seller", null));
        User user = userRepository.save(User.register("user@vintic.local", "user", null));
        Product product = persistProduct(seller);
        Auction auction = persistAuction(product);
        penaltyRepository.save(Penalty.forfeited(user, auction));

        assertThat(penaltyRepository.findByUser_IdOrderByCreatedAtDesc(user.getId())).hasSize(1);
    }

    @Test
    void serverTime은_주입된_Clock_기준_현재시각이다() {
        User user = userRepository.save(User.register("user@vintic.local", "user", null));

        MyPenaltyResponse response = penaltyQueryService.getMyPenalties(user.getId());

        assertThat(response.serverTime().toLocalDateTime()).isEqualTo(FIXED_NOW);
    }

    @Test
    void 존재하지_않는_사용자를_조회하면_예외가_발생한다() {
        assertThatThrownBy(() -> penaltyQueryService.getMyPenalties(9999L))
                .isInstanceOf(UserNotFoundException.class);
    }
}
