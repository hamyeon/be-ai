package com.vintic.backend.purchasegoal.service;

import com.vintic.backend.ai.purchase.dto.GoalCondition;
import com.vintic.backend.ai.purchase.model.ModelAliases;
import com.vintic.backend.ai.purchase.price.PriceEstimate;
import com.vintic.backend.ai.purchase.price.PriceEstimateProvider;
import com.vintic.backend.ai.purchase.price.PriceEstimateQuery;
import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.autobid.domain.AutoBidSetting;
import com.vintic.backend.config.ClockConfig;
import com.vintic.backend.product.domain.Product;
import com.vintic.backend.purchasegoal.domain.PurchaseGoal;
import com.vintic.backend.support.TestClockConfig;
import com.vintic.backend.user.domain.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

// Day 3 사전 필터 범위만 검증한다 - Matcher 호출/이력 저장/순위/Scheduler/Goal 상태 전이는 없다.
@DataJpaTest
@Import({
        PurchaseGoalCandidateFinder.class,
        ModelAliases.class,
        TestClockConfig.class,
        PurchaseGoalCandidateFinderTest.FakePriceEstimateProviderConfig.class
})
class PurchaseGoalCandidateFinderTest {

    private static final LocalDateTime FIXED_NOW = LocalDateTime.ofInstant(TestClockConfig.FIXED_INSTANT, ClockConfig.APP_ZONE);

    @Autowired
    private PurchaseGoalCandidateFinder finder;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private FakePriceEstimateProvider fakePriceEstimateProvider;

    // FakePriceEstimateProvider는 Spring 컨텍스트가 캐시하는 singleton bean이다 - 한 테스트가
    // respondWith()로 바꾼 동작이 다음 테스트로 새어나가지 않도록 매 테스트 전에 기본값으로 되돌린다.
    @BeforeEach
    void resetPriceEstimateProvider() {
        fakePriceEstimateProvider.respondWith(q -> Optional.of(FakePriceEstimateProvider.validEstimate()));
    }

    @TestConfiguration
    static class FakePriceEstimateProviderConfig {
        @Bean
        FakePriceEstimateProvider priceEstimateProvider() {
            return new FakePriceEstimateProvider();
        }
    }

    // 기본값은 항상 유효한 시세를 준다. 테스트별로 respondWith/throwFor로 특정 상품만 다르게 만든다.
    static class FakePriceEstimateProvider implements PriceEstimateProvider {
        private Function<PriceEstimateQuery, Optional<PriceEstimate>> behavior = q -> Optional.of(validEstimate());

        @Override
        public Optional<PriceEstimate> estimate(PriceEstimateQuery query) {
            return behavior.apply(query);
        }

        void respondWith(Function<PriceEstimateQuery, Optional<PriceEstimate>> newBehavior) {
            this.behavior = newBehavior;
        }

        static PriceEstimate validEstimate() {
            return new PriceEstimate(300000, 280000, 320000, PriceEstimate.Source.USED_MARKET, 12, "사유", FIXED_NOW);
        }
    }

    private User persistUser(String email) {
        User user = User.register(email, email, null);
        entityManager.persist(user);
        return user;
    }

    private Product persistProduct(User seller, String brand, String model, String conditionGrade, Integer sizeKr) {
        Product product = new Product(
                seller,
                List.of("https://example.com/a.jpg"),
                brand, model, "Panda", sizeKr, conditionGrade, "PARTIAL",
                300000, 350000, "280,000원 ~ 320,000원", 290000, "사유", "설명"
        );
        entityManager.persist(product);
        return product;
    }

    private Auction persistAuction(Product product, LocalDateTime startAt, LocalDateTime endAt, boolean live) {
        Auction auction = Auction.schedule(product, 100000L, 5000L, startAt, endAt);
        if (live) {
            auction.start();
        }
        entityManager.persist(auction);
        return auction;
    }

    private PurchaseGoal persistGoal(
            User owner, String brand, String modelKey, GoalCondition minCondition, Integer sizeKr, Long hardMaxAmount
    ) {
        PurchaseGoal goal = PurchaseGoal.create(
                owner, brand, modelKey, "설명", minCondition, sizeKr, hardMaxAmount, null,
                FIXED_NOW.plusDays(7), FIXED_NOW
        );
        entityManager.persist(goal);
        return goal;
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    // ---------- 시간 경계 ----------

    @Test
    void 진행중인_LIVE_경매는_후보다() {
        User seller = persistUser("seller1@vintic.local");
        User buyer = persistUser("buyer1@vintic.local");
        Product product = persistProduct(seller, "New Balance", "990v6", "A", 270);
        Auction live = persistAuction(product, FIXED_NOW.minusHours(1), FIXED_NOW.plusHours(1), true);
        PurchaseGoal goal = persistGoal(buyer, "New Balance", null, GoalCondition.B, null, 500000L);
        flushAndClear();

        List<PurchaseGoalCandidate> candidates = finder.findCandidates(reload(goal));

        assertThat(candidates).extracting(c -> c.auction().getId()).containsExactly(live.getId());
    }

    @Test
    void endAt이_지난_LIVE_경매는_제외한다() {
        User seller = persistUser("seller2@vintic.local");
        User buyer = persistUser("buyer2@vintic.local");
        Product product = persistProduct(seller, "New Balance", "990v6", "A", 270);
        persistAuction(product, FIXED_NOW.minusDays(2), FIXED_NOW.minusMinutes(1), true);
        PurchaseGoal goal = persistGoal(buyer, "New Balance", null, GoalCondition.B, null, 500000L);
        flushAndClear();

        List<PurchaseGoalCandidate> candidates = finder.findCandidates(reload(goal));

        assertThat(candidates).isEmpty();
    }

    @Test
    void 시작까지_24시간_이내인_SCHEDULED_경매는_후보다() {
        User seller = persistUser("seller3@vintic.local");
        User buyer = persistUser("buyer3@vintic.local");
        Product product = persistProduct(seller, "New Balance", "990v6", "A", 270);
        Auction scheduled = persistAuction(product, FIXED_NOW.plusHours(23), FIXED_NOW.plusDays(3), false);
        PurchaseGoal goal = persistGoal(buyer, "New Balance", null, GoalCondition.B, null, 500000L);
        flushAndClear();

        List<PurchaseGoalCandidate> candidates = finder.findCandidates(reload(goal));

        assertThat(candidates).extracting(c -> c.auction().getId()).containsExactly(scheduled.getId());
    }

    @Test
    void 시작까지_24시간_넘게_남은_SCHEDULED_경매는_제외한다() {
        User seller = persistUser("seller4@vintic.local");
        User buyer = persistUser("buyer4@vintic.local");
        Product product = persistProduct(seller, "New Balance", "990v6", "A", 270);
        persistAuction(product, FIXED_NOW.plusHours(25), FIXED_NOW.plusDays(3), false);
        PurchaseGoal goal = persistGoal(buyer, "New Balance", null, GoalCondition.B, null, 500000L);
        flushAndClear();

        List<PurchaseGoalCandidate> candidates = finder.findCandidates(reload(goal));

        assertThat(candidates).isEmpty();
    }

    // ---------- 브랜드 · 모델키 ----------

    @Test
    void modelKey가_없으면_브랜드를_별칭표로_정규화해서_비교한다() {
        User seller = persistUser("seller5@vintic.local");
        User buyer = persistUser("buyer5@vintic.local");
        Product product = persistProduct(seller, "New Balance", "990v6", "A", 270);
        Auction auction = persistAuction(product, FIXED_NOW.minusHours(1), FIXED_NOW.plusHours(1), true);
        // 사용자가 "뉴발란스"로 입력해도 Product.brand("New Balance")와 BrandAliases로 같은 브랜드로 본다.
        PurchaseGoal goal = persistGoal(buyer, "뉴발란스", null, GoalCondition.B, null, 500000L);
        flushAndClear();

        List<PurchaseGoalCandidate> candidates = finder.findCandidates(reload(goal));

        assertThat(candidates).extracting(c -> c.auction().getId()).containsExactly(auction.getId());
    }

    @Test
    void 별칭표_밖_브랜드는_대소문자만_무시하고_비교한다() {
        User seller = persistUser("seller6@vintic.local");
        User buyer = persistUser("buyer6@vintic.local");
        Product product = persistProduct(seller, "Zephyr Craft", "트레일화", "A", 270);
        Auction auction = persistAuction(product, FIXED_NOW.minusHours(1), FIXED_NOW.plusHours(1), true);
        PurchaseGoal goal = persistGoal(buyer, "zephyr craft", null, GoalCondition.B, null, 500000L);
        flushAndClear();

        List<PurchaseGoalCandidate> candidates = finder.findCandidates(reload(goal));

        assertThat(candidates).extracting(c -> c.auction().getId()).containsExactly(auction.getId());
    }

    @Test
    void 브랜드가_다르면_제외한다() {
        User seller = persistUser("seller7@vintic.local");
        User buyer = persistUser("buyer7@vintic.local");
        Product product = persistProduct(seller, "Asics", "젤 카야노", "A", 270);
        persistAuction(product, FIXED_NOW.minusHours(1), FIXED_NOW.plusHours(1), true);
        PurchaseGoal goal = persistGoal(buyer, "New Balance", null, GoalCondition.B, null, 500000L);
        flushAndClear();

        List<PurchaseGoalCandidate> candidates = finder.findCandidates(reload(goal));

        assertThat(candidates).isEmpty();
    }

    @Test
    void modelKey가_있으면_브랜드_문자열_비교_없이_모델키로만_판단한다() {
        User seller = persistUser("seller8@vintic.local");
        User buyer = persistUser("buyer8@vintic.local");
        // 상품 브랜드 표기가 goal.brand와 달라도(New Balance vs 뉴발란스 미기재) modelKey가 맞으면 통과한다.
        Product product = persistProduct(seller, "New Balance", "990v6", "A", 270);
        Auction auction = persistAuction(product, FIXED_NOW.minusHours(1), FIXED_NOW.plusHours(1), true);
        PurchaseGoal goal = persistGoal(buyer, null, "nb990", GoalCondition.B, null, 500000L);
        flushAndClear();

        List<PurchaseGoalCandidate> candidates = finder.findCandidates(reload(goal));

        assertThat(candidates).extracting(c -> c.auction().getId()).containsExactly(auction.getId());
    }

    @Test
    void modelKey가_다르면_제외한다() {
        User seller = persistUser("seller9@vintic.local");
        User buyer = persistUser("buyer9@vintic.local");
        Product product = persistProduct(seller, "New Balance", "993", "A", 270);
        persistAuction(product, FIXED_NOW.minusHours(1), FIXED_NOW.plusHours(1), true);
        PurchaseGoal goal = persistGoal(buyer, null, "nb990", GoalCondition.B, null, 500000L);
        flushAndClear();

        List<PurchaseGoalCandidate> candidates = finder.findCandidates(reload(goal));

        assertThat(candidates).isEmpty();
    }

    @Test
    void modelKey는_있는데_상품에서_카탈로그_모델을_못_읽으면_제외한다() {
        User seller = persistUser("seller10@vintic.local");
        User buyer = persistUser("buyer10@vintic.local");
        Product product = persistProduct(seller, "Unknown Brand", "알수없는모델", "A", 270);
        persistAuction(product, FIXED_NOW.minusHours(1), FIXED_NOW.plusHours(1), true);
        PurchaseGoal goal = persistGoal(buyer, null, "nb990", GoalCondition.B, null, 500000L);
        flushAndClear();

        List<PurchaseGoalCandidate> candidates = finder.findCandidates(reload(goal));

        assertThat(candidates).isEmpty();
    }

    // ---------- 등급 · 사이즈 ----------

    @Test
    void 상품_등급이_UNKNOWN이면_제외한다() {
        User seller = persistUser("seller11@vintic.local");
        User buyer = persistUser("buyer11@vintic.local");
        Product product = persistProduct(seller, "New Balance", "990v6", "UNKNOWN", 270);
        persistAuction(product, FIXED_NOW.minusHours(1), FIXED_NOW.plusHours(1), true);
        PurchaseGoal goal = persistGoal(buyer, "New Balance", null, GoalCondition.B, null, 500000L);
        flushAndClear();

        List<PurchaseGoalCandidate> candidates = finder.findCandidates(reload(goal));

        assertThat(candidates).isEmpty();
    }

    @Test
    void 상품_등급이_최소_조건보다_낮으면_제외한다() {
        User seller = persistUser("seller12@vintic.local");
        User buyer = persistUser("buyer12@vintic.local");
        Product product = persistProduct(seller, "New Balance", "990v6", "C", 270);
        persistAuction(product, FIXED_NOW.minusHours(1), FIXED_NOW.plusHours(1), true);
        PurchaseGoal goal = persistGoal(buyer, "New Balance", null, GoalCondition.A, null, 500000L);
        flushAndClear();

        List<PurchaseGoalCandidate> candidates = finder.findCandidates(reload(goal));

        assertThat(candidates).isEmpty();
    }

    @Test
    void 사이즈가_지정되면_정확히_일치해야_한다() {
        User seller = persistUser("seller13@vintic.local");
        User buyer = persistUser("buyer13@vintic.local");
        Product product = persistProduct(seller, "New Balance", "990v6", "A", 275);
        persistAuction(product, FIXED_NOW.minusHours(1), FIXED_NOW.plusHours(1), true);
        PurchaseGoal goal = persistGoal(buyer, "New Balance", null, GoalCondition.B, 270, 500000L);
        flushAndClear();

        List<PurchaseGoalCandidate> candidates = finder.findCandidates(reload(goal));

        assertThat(candidates).isEmpty();
    }

    @Test
    void 사이즈가_없으면_사이즈로_거르지_않는다() {
        User seller = persistUser("seller14@vintic.local");
        User buyer = persistUser("buyer14@vintic.local");
        Product product = persistProduct(seller, "New Balance", "990v6", "A", 275);
        Auction auction = persistAuction(product, FIXED_NOW.minusHours(1), FIXED_NOW.plusHours(1), true);
        PurchaseGoal goal = persistGoal(buyer, "New Balance", null, GoalCondition.B, null, 500000L);
        flushAndClear();

        List<PurchaseGoalCandidate> candidates = finder.findCandidates(reload(goal));

        assertThat(candidates).extracting(c -> c.auction().getId()).containsExactly(auction.getId());
    }

    // ---------- 기존 AutoBid 보유 ----------

    @Test
    void 취소된_이력이라도_사용자의_AutoBidSetting이_있으면_제외한다() {
        User seller = persistUser("seller15@vintic.local");
        User buyer = persistUser("buyer15@vintic.local");
        Product product = persistProduct(seller, "New Balance", "990v6", "A", 270);
        Auction auction = persistAuction(product, FIXED_NOW.minusHours(1), FIXED_NOW.plusHours(1), true);
        AutoBidSetting existing = AutoBidSetting.reserve(auction, buyer, 200000L);
        existing.activate();
        existing.cancel();
        entityManager.persist(existing);
        PurchaseGoal goal = persistGoal(buyer, "New Balance", null, GoalCondition.B, null, 500000L);
        flushAndClear();

        List<PurchaseGoalCandidate> candidates = finder.findCandidates(reload(goal));

        assertThat(candidates).isEmpty();
    }

    @Test
    void 다른_사용자의_AutoBidSetting은_제외_사유가_아니다() {
        User seller = persistUser("seller16@vintic.local");
        User otherUser = persistUser("other16@vintic.local");
        User buyer = persistUser("buyer16@vintic.local");
        Product product = persistProduct(seller, "New Balance", "990v6", "A", 270);
        Auction auction = persistAuction(product, FIXED_NOW.minusHours(1), FIXED_NOW.plusHours(1), true);
        entityManager.persist(AutoBidSetting.reserve(auction, otherUser, 200000L));
        PurchaseGoal goal = persistGoal(buyer, "New Balance", null, GoalCondition.B, null, 500000L);
        flushAndClear();

        List<PurchaseGoalCandidate> candidates = finder.findCandidates(reload(goal));

        assertThat(candidates).extracting(c -> c.auction().getId()).containsExactly(auction.getId());
    }

    // ---------- 예산 부족 ----------

    @Test
    void hardMaxAmount가_최소_가능_금액보다_낮으면_제외한다() {
        User seller = persistUser("seller17@vintic.local");
        User buyer = persistUser("buyer17@vintic.local");
        Product product = persistProduct(seller, "New Balance", "990v6", "A", 270);
        // currentPrice=100000, bidIncrement=5000 -> minNextBidAmount=105000
        persistAuction(product, FIXED_NOW.minusHours(1), FIXED_NOW.plusHours(1), true);
        PurchaseGoal goal = persistGoal(buyer, "New Balance", null, GoalCondition.B, null, 100000L);
        flushAndClear();

        List<PurchaseGoalCandidate> candidates = finder.findCandidates(reload(goal));

        assertThat(candidates).isEmpty();
    }

    // ---------- 서버 시세 부재 ----------

    @Test
    void 서버_시세가_없으면_제외한다() {
        fakePriceEstimateProvider.respondWith(q -> Optional.empty());
        User seller = persistUser("seller18@vintic.local");
        User buyer = persistUser("buyer18@vintic.local");
        Product product = persistProduct(seller, "New Balance", "990v6", "A", 270);
        persistAuction(product, FIXED_NOW.minusHours(1), FIXED_NOW.plusHours(1), true);
        PurchaseGoal goal = persistGoal(buyer, "New Balance", null, GoalCondition.B, null, 500000L);
        flushAndClear();

        List<PurchaseGoalCandidate> candidates = finder.findCandidates(reload(goal));

        assertThat(candidates).isEmpty();
    }

    @Test
    void 시세_계산이_예외를_던져도_다른_후보_평가는_계속된다() {
        User seller = persistUser("seller19@vintic.local");
        User buyer = persistUser("buyer19@vintic.local");
        Product failing = persistProduct(seller, "New Balance", "990v6", "A", 270);
        Product ok = persistProduct(seller, "New Balance", "990v6", "A", 271);
        persistAuction(failing, FIXED_NOW.minusHours(1), FIXED_NOW.plusHours(1), true);
        Auction okAuction = persistAuction(ok, FIXED_NOW.minusHours(1), FIXED_NOW.plusHours(1), true);
        fakePriceEstimateProvider.respondWith(q -> {
            if (q.sizeKr() != null && q.sizeKr() == 270) {
                throw new RuntimeException("시세 서비스 장애");
            }
            return Optional.of(FakePriceEstimateProvider.validEstimate());
        });
        PurchaseGoal goal = persistGoal(buyer, "New Balance", null, GoalCondition.B, null, 500000L);
        flushAndClear();

        List<PurchaseGoalCandidate> candidates = finder.findCandidates(reload(goal));

        assertThat(candidates).extracting(c -> c.auction().getId()).containsExactly(okAuction.getId());
    }

    @Test
    void 통과한_후보에는_서버_시세가_함께_담긴다() {
        User seller = persistUser("seller20@vintic.local");
        User buyer = persistUser("buyer20@vintic.local");
        Product product = persistProduct(seller, "New Balance", "990v6", "A", 270);
        Auction auction = persistAuction(product, FIXED_NOW.minusHours(1), FIXED_NOW.plusHours(1), true);
        PurchaseGoal goal = persistGoal(buyer, "New Balance", null, GoalCondition.B, null, 500000L);
        flushAndClear();

        List<PurchaseGoalCandidate> candidates = finder.findCandidates(reload(goal));

        assertThat(candidates).hasSize(1);
        PurchaseGoalCandidate candidate = candidates.get(0);
        assertThat(candidate.auction().getId()).isEqualTo(auction.getId());
        assertThat(candidate.priceEstimate().estimatedPrice()).isEqualTo(300000);
    }

    private PurchaseGoal reload(PurchaseGoal goal) {
        return entityManager.find(PurchaseGoal.class, goal.getId());
    }
}
