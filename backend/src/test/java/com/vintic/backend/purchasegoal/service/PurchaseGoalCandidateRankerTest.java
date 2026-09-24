package com.vintic.backend.purchasegoal.service;

import com.vintic.backend.ai.purchase.dto.GoalCondition;
import com.vintic.backend.ai.purchase.match.AuctionListing;
import com.vintic.backend.ai.purchase.match.ListingMatcher;
import com.vintic.backend.ai.purchase.match.MatchGoal;
import com.vintic.backend.ai.purchase.match.MatchResult;
import com.vintic.backend.ai.purchase.price.PriceEstimate;
import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.config.ClockConfig;
import com.vintic.backend.product.domain.Product;
import com.vintic.backend.purchasegoal.domain.PurchaseGoal;
import com.vintic.backend.purchasegoal.domain.PurchaseGoalMatch;
import com.vintic.backend.purchasegoal.repository.PurchaseGoalMatchRepository;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

// Day 4 사전 필터 이후 단계만 검증한다 - Scheduler/Goal 상태 전이/AutoBid 생성/settlement는 없다.
@DataJpaTest
@Import({
        PurchaseGoalCandidateRanker.class,
        TestClockConfig.class,
        PurchaseGoalCandidateRankerTest.FakeListingMatcherConfig.class
})
class PurchaseGoalCandidateRankerTest {

    private static final LocalDateTime FIXED_NOW = LocalDateTime.ofInstant(TestClockConfig.FIXED_INSTANT, ClockConfig.APP_ZONE);

    @Autowired
    private PurchaseGoalCandidateRanker ranker;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PurchaseGoalMatchRepository purchaseGoalMatchRepository;

    @Autowired
    private FakeListingMatcher fakeListingMatcher;

    @TestConfiguration
    static class FakeListingMatcherConfig {
        @Bean
        FakeListingMatcher listingMatcher() {
            return new FakeListingMatcher();
        }
    }

    static class FakeListingMatcher implements ListingMatcher {
        private Function<AuctionListing, MatchResult> behavior =
                listing -> new MatchResult(true, 0.5, "기본 매칭", null);
        private final List<Long> calledAuctionIds = new ArrayList<>();

        @Override
        public MatchResult evaluate(MatchGoal goal, AuctionListing listing) {
            calledAuctionIds.add(listing.auctionId());
            return behavior.apply(listing);
        }

        void respondWith(Function<AuctionListing, MatchResult> newBehavior) {
            this.behavior = newBehavior;
        }

        void reset() {
            behavior = listing -> new MatchResult(true, 0.5, "기본 매칭", null);
            calledAuctionIds.clear();
        }
    }

    @BeforeEach
    void resetFakeMatcher() {
        fakeListingMatcher.reset();
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
                "New Balance", "990v6", "Grey", 270, "A", "PARTIAL",
                300000, 350000, "280,000원 ~ 320,000원", 290000, "사유", "설명"
        );
        entityManager.persist(product);
        return product;
    }

    private Auction persistAuction(Product product, long startPrice, long bidIncrement, LocalDateTime startAt, LocalDateTime endAt) {
        Auction auction = Auction.schedule(product, startPrice, bidIncrement, startAt, endAt);
        auction.start();
        entityManager.persist(auction);
        return auction;
    }

    private PurchaseGoal persistGoal(User owner, Long hardMaxAmount) {
        PurchaseGoal goal = PurchaseGoal.create(
                owner, "New Balance", "nb990", "뉴발란스 990",
                GoalCondition.B, null, hardMaxAmount, null,
                FIXED_NOW.plusDays(7), FIXED_NOW
        );
        entityManager.persist(goal);
        return goal;
    }

    private PriceEstimate estimate(int estimatedPrice) {
        return new PriceEstimate(estimatedPrice, estimatedPrice - 20000, estimatedPrice + 20000,
                PriceEstimate.Source.USED_MARKET, 10, "사유", FIXED_NOW);
    }

    private PurchaseGoalMatch persistCachedMatch(Long goalId, Long auctionId, boolean matched, double semanticScore, String reason) {
        PurchaseGoalMatch match = PurchaseGoalMatch.create(goalId, auctionId, matched, semanticScore, reason, "nb990", FIXED_NOW);
        entityManager.persist(match);
        return match;
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    // ---------- 캐시 재사용 ----------

    @Test
    void 캐시된_matched_true_이력은_Matcher를_다시_호출하지_않고_재사용한다() {
        User seller = persistUser("seller1@vintic.local");
        User buyer = persistUser("buyer1@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistAuction(product, 100000L, 5000L, FIXED_NOW.minusHours(1), FIXED_NOW.plusHours(1));
        PurchaseGoal goal = persistGoal(buyer, 500000L);
        persistCachedMatch(goal.getId(), auction.getId(), true, 0.9, "캐시된 일치");
        flushAndClear();

        List<PurchaseGoalCandidate> candidates = List.of(new PurchaseGoalCandidate(reload(auction), estimate(200000)));
        Optional<PurchaseGoalRankedCandidate> result = ranker.rankTopCandidate(reload(goal), candidates);

        assertThat(result).isPresent();
        assertThat(result.get().matchResult().reason()).isEqualTo("캐시된 일치");
        assertThat(fakeListingMatcher.calledAuctionIds).isEmpty();
    }

    @Test
    void 캐시된_matched_false_이력도_재사용하고_후보에서_제외한다() {
        User seller = persistUser("seller2@vintic.local");
        User buyer = persistUser("buyer2@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistAuction(product, 100000L, 5000L, FIXED_NOW.minusHours(1), FIXED_NOW.plusHours(1));
        PurchaseGoal goal = persistGoal(buyer, 500000L);
        persistCachedMatch(goal.getId(), auction.getId(), false, 0.0, "캐시된 불일치");
        flushAndClear();

        List<PurchaseGoalCandidate> candidates = List.of(new PurchaseGoalCandidate(reload(auction), estimate(200000)));
        Optional<PurchaseGoalRankedCandidate> result = ranker.rankTopCandidate(reload(goal), candidates);

        assertThat(result).isEmpty();
        assertThat(fakeListingMatcher.calledAuctionIds).isEmpty();
    }

    // ---------- Matcher 실패 후 재시도 ----------

    @Test
    void Matcher가_예외를_던지면_이력을_저장하지_않아_다음_평가에서_재시도할_수_있다() {
        User seller = persistUser("seller3@vintic.local");
        User buyer = persistUser("buyer3@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistAuction(product, 100000L, 5000L, FIXED_NOW.minusHours(1), FIXED_NOW.plusHours(1));
        PurchaseGoal goal = persistGoal(buyer, 500000L);
        fakeListingMatcher.respondWith(listing -> {
            throw new RuntimeException("Matcher 타임아웃");
        });
        flushAndClear();

        List<PurchaseGoalCandidate> candidates = List.of(new PurchaseGoalCandidate(reload(auction), estimate(200000)));
        Optional<PurchaseGoalRankedCandidate> result = ranker.rankTopCandidate(reload(goal), candidates);

        assertThat(result).isEmpty();
        assertThat(purchaseGoalMatchRepository.findByGoalIdAndAuctionIdIn(goal.getId(), List.of(auction.getId()))).isEmpty();
    }

    @Test
    void 새로_평가한_matched_false_결과는_저장되지만_후보에서는_제외한다() {
        User seller = persistUser("seller4@vintic.local");
        User buyer = persistUser("buyer4@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistAuction(product, 100000L, 5000L, FIXED_NOW.minusHours(1), FIXED_NOW.plusHours(1));
        PurchaseGoal goal = persistGoal(buyer, 500000L);
        fakeListingMatcher.respondWith(listing -> new MatchResult(false, 0.0, "새로 평가한 불일치", null));
        flushAndClear();

        List<PurchaseGoalCandidate> candidates = List.of(new PurchaseGoalCandidate(reload(auction), estimate(200000)));
        Optional<PurchaseGoalRankedCandidate> result = ranker.rankTopCandidate(reload(goal), candidates);

        assertThat(result).isEmpty();
        List<PurchaseGoalMatch> saved = purchaseGoalMatchRepository.findByGoalIdAndAuctionIdIn(goal.getId(), List.of(auction.getId()));
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).isMatched()).isFalse();
    }

    // ---------- 예산 상한 · 최소 입찰 금액 ----------

    @Test
    void cap은_hardMaxAmount와_서버_시세_중_작은_값이다() {
        User seller = persistUser("seller5@vintic.local");
        User buyer = persistUser("buyer5@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistAuction(product, 100000L, 5000L, FIXED_NOW.minusHours(1), FIXED_NOW.plusHours(1));
        PurchaseGoal goal = persistGoal(buyer, 150000L); // hardMaxAmount < estimatedPrice
        flushAndClear();

        List<PurchaseGoalCandidate> candidates = List.of(new PurchaseGoalCandidate(reload(auction), estimate(300000)));
        Optional<PurchaseGoalRankedCandidate> result = ranker.rankTopCandidate(reload(goal), candidates);

        assertThat(result).isPresent();
        assertThat(result.get().cap()).isEqualTo(150000L);
    }

    @Test
    void cap이_최소_입찰_가능_금액보다_낮으면_제외한다() {
        User seller = persistUser("seller6@vintic.local");
        User buyer = persistUser("buyer6@vintic.local");
        Product product = persistProduct(seller);
        // currentPrice=99000, bidIncrement=5000 -> minNextBidAmount=104000
        Auction auction = persistAuction(product, 99000L, 5000L, FIXED_NOW.minusHours(1), FIXED_NOW.plusHours(1));
        PurchaseGoal goal = persistGoal(buyer, 100000L); // cap=100000 < 104000
        flushAndClear();

        List<PurchaseGoalCandidate> candidates = List.of(new PurchaseGoalCandidate(reload(auction), estimate(300000)));
        Optional<PurchaseGoalRankedCandidate> result = ranker.rankTopCandidate(reload(goal), candidates);

        assertThat(result).isEmpty();
    }

    // ---------- 4단계 순위 동률 해소 ----------

    @Test
    void 할인율이_높은_후보를_우선한다() {
        User seller = persistUser("seller7@vintic.local");
        User buyer = persistUser("buyer7@vintic.local");
        Product product = persistProduct(seller);
        // A: (200000-100000)/200000=0.5, B: (200000-150000)/200000=0.25
        Auction low = persistAuction(product, 150000L, 5000L, FIXED_NOW.minusHours(1), FIXED_NOW.plusHours(5));
        Auction high = persistAuction(product, 100000L, 5000L, FIXED_NOW.minusHours(1), FIXED_NOW.plusHours(1));
        PurchaseGoal goal = persistGoal(buyer, 500000L);
        flushAndClear();

        List<PurchaseGoalCandidate> candidates = List.of(
                new PurchaseGoalCandidate(reload(low), estimate(200000)),
                new PurchaseGoalCandidate(reload(high), estimate(200000))
        );
        Optional<PurchaseGoalRankedCandidate> result = ranker.rankTopCandidate(reload(goal), candidates);

        assertThat(result).isPresent();
        assertThat(result.get().auction().getId()).isEqualTo(high.getId());
    }

    @Test
    void 할인율이_같으면_종료_시각이_빠른_후보를_우선한다() {
        User seller = persistUser("seller8@vintic.local");
        User buyer = persistUser("buyer8@vintic.local");
        Product product = persistProduct(seller);
        // 둘 다 (200000-100000)/200000=0.5로 동일. endAt만 다르다.
        // id가 더 작은 쪽이 끝나는 시각은 더 늦게 - endAt 우선이 auctionId보다 먼저 이겨야 통과한다.
        Auction laterEnd = persistAuction(product, 100000L, 5000L, FIXED_NOW.minusHours(1), FIXED_NOW.plusHours(5));
        Auction earlierEnd = persistAuction(product, 100000L, 5000L, FIXED_NOW.minusHours(1), FIXED_NOW.plusHours(1));
        PurchaseGoal goal = persistGoal(buyer, 500000L);
        flushAndClear();

        List<PurchaseGoalCandidate> candidates = List.of(
                new PurchaseGoalCandidate(reload(laterEnd), estimate(200000)),
                new PurchaseGoalCandidate(reload(earlierEnd), estimate(200000))
        );
        Optional<PurchaseGoalRankedCandidate> result = ranker.rankTopCandidate(reload(goal), candidates);

        assertThat(result).isPresent();
        assertThat(result.get().auction().getId()).isEqualTo(earlierEnd.getId());
    }

    @Test
    void 할인율과_종료시각이_같으면_semanticScore가_높은_후보를_우선한다() {
        User seller = persistUser("seller9@vintic.local");
        User buyer = persistUser("buyer9@vintic.local");
        Product product = persistProduct(seller);
        LocalDateTime sharedEndAt = FIXED_NOW.plusHours(3);
        // 낮은 semanticScore 쪽을 먼저 persist해 더 작은 auctionId를 갖게 한다 -
        // semanticScore가 auctionId보다 먼저 이겨야 이 테스트가 의미가 있다.
        Auction lowScore = persistAuction(product, 100000L, 5000L, FIXED_NOW.minusHours(1), sharedEndAt);
        Auction highScore = persistAuction(product, 100000L, 5000L, FIXED_NOW.minusHours(1), sharedEndAt);
        PurchaseGoal goal = persistGoal(buyer, 500000L);
        persistCachedMatch(goal.getId(), lowScore.getId(), true, 0.2, "낮은 점수");
        persistCachedMatch(goal.getId(), highScore.getId(), true, 0.9, "높은 점수");
        flushAndClear();

        List<PurchaseGoalCandidate> candidates = List.of(
                new PurchaseGoalCandidate(reload(lowScore), estimate(200000)),
                new PurchaseGoalCandidate(reload(highScore), estimate(200000))
        );
        Optional<PurchaseGoalRankedCandidate> result = ranker.rankTopCandidate(reload(goal), candidates);

        assertThat(result).isPresent();
        assertThat(result.get().auction().getId()).isEqualTo(highScore.getId());
    }

    @Test
    void 할인율_종료시각_semanticScore가_모두_같으면_auctionId가_작은_후보를_우선한다() {
        User seller = persistUser("seller10@vintic.local");
        User buyer = persistUser("buyer10@vintic.local");
        Product product = persistProduct(seller);
        LocalDateTime sharedEndAt = FIXED_NOW.plusHours(3);
        Auction first = persistAuction(product, 100000L, 5000L, FIXED_NOW.minusHours(1), sharedEndAt);
        Auction second = persistAuction(product, 100000L, 5000L, FIXED_NOW.minusHours(1), sharedEndAt);
        PurchaseGoal goal = persistGoal(buyer, 500000L);
        persistCachedMatch(goal.getId(), first.getId(), true, 0.5, "동일 점수");
        persistCachedMatch(goal.getId(), second.getId(), true, 0.5, "동일 점수");
        flushAndClear();

        List<PurchaseGoalCandidate> candidates = List.of(
                new PurchaseGoalCandidate(reload(second), estimate(200000)),
                new PurchaseGoalCandidate(reload(first), estimate(200000))
        );
        Optional<PurchaseGoalRankedCandidate> result = ranker.rankTopCandidate(reload(goal), candidates);

        assertThat(result).isPresent();
        assertThat(result.get().auction().getId()).isEqualTo(first.getId());
    }

    private Auction reload(Auction auction) {
        return entityManager.find(Auction.class, auction.getId());
    }

    private PurchaseGoal reload(PurchaseGoal goal) {
        return entityManager.find(PurchaseGoal.class, goal.getId());
    }
}
