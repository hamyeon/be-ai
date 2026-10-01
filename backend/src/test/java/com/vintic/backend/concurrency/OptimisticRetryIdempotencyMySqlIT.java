package com.vintic.backend.concurrency;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.autobid.domain.AutoBidSetting;
import com.vintic.backend.autobid.domain.AutoBidSettingStatus;
import com.vintic.backend.autobid.repository.AutoBidSettingRepository;
import com.vintic.backend.bid.domain.Bid;
import com.vintic.backend.bid.domain.BidType;
import com.vintic.backend.bid.domain.Idempotency;
import com.vintic.backend.bid.repository.BidRepository;
import com.vintic.backend.bid.repository.IdempotencyRepository;
import com.vintic.backend.bid.service.BidCommandService;
import com.vintic.backend.bid.service.IdempotencyClaimService;
import com.vintic.backend.bid.service.ManualBidAttemptExecutor;
import com.vintic.backend.bid.service.OptimisticBidAttemptService;
import com.vintic.backend.bid.service.OptimisticBidOutcome;
import com.vintic.backend.bid.service.OptimisticBidRetryOrchestrator;
import com.vintic.backend.bid.service.OptimisticManualBidService;
import com.vintic.backend.bid.service.OptimisticRetryExhaustedException;
import com.vintic.backend.common.exception.BidAmountTooLowException;
import com.vintic.backend.common.exception.IdempotencyPayloadMismatchException;
import com.vintic.backend.product.domain.Product;
import com.vintic.backend.product.repository.ProductRepository;
import com.vintic.backend.user.domain.User;
import com.vintic.backend.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #74-2: 실제 MySQL(Testcontainers, InnoDB)에서 (1) {@code @Version} conflict 실증,
 * (2) Idempotency claim과 optimistic retry의 트랜잭션 관계, (3) partial state/duplicate Bid
 * 방지를 검증한다. #74-1의 retry 구조({@link OptimisticBidRetryOrchestrator}/
 * {@link OptimisticBidAttemptService}, {@code MAX_ATTEMPTS=5}, backoff 없음)는 그대로
 * 재사용하고 다시 설계하지 않는다 - 이 클래스는 그 구조를
 * {@link OptimisticManualBidService}(#74-2 신규, production {@link com.vintic.backend.bid.service.ManualBidService}와
 * 동일한 얇은 진입점 + 기존 {@link IdempotencyClaimService} 위임 구조)로 감싸서 검증한다.
 *
 * <p>conflict를 유도하는 두 가지 test-only harness:
 * <ul>
 *   <li>{@link ConflictGate}: {@code CountDownLatch} 2개로 실제 두 스레드(genuine concurrent
 *       transaction)의 순서를 결정적으로 고정한다 - "실제 동시 트랜잭션" 시나리오(§1) 전용.</li>
 *   <li>{@link ConflictHammer}: 같은 스레드에서 {@code findById()} 직후 즉시 별도
 *       {@code REQUIRES_NEW} 트랜잭션으로 경쟁 커밋을 주입한다 - exhaustion(5회 연속 conflict)처럼
 *       여러 번 결정적으로 재현해야 하는 시나리오 전용. wall-clock 동시성은 아니지만, 매번 "이미
 *       커밋된 다른 트랜잭션이 존재하는 상태에서" 읽은 stale entity로 commit을 시도한다는
 *       @Version 충돌의 본질은 동일하다(완료보고에 이 차이를 명시).</li>
 * </ul>
 * 둘 다 test-only({@code @TestConfiguration})이고 production delay는 추가하지 않는다.
 * production {@code findByIdForUpdate()} 경로는 건드리지 않는다 - non-locking {@code findById()}만 감싼다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Testcontainers
class OptimisticRetryIdempotencyMySqlIT {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    static class ConflictGate {
        private volatile long targetAuctionId = -1;
        private volatile CountDownLatch loaded;
        private volatile CountDownLatch proceed;
        private final AtomicBoolean armed = new AtomicBoolean(false);

        void arm(long auctionId, CountDownLatch loaded, CountDownLatch proceed) {
            this.targetAuctionId = auctionId;
            this.loaded = loaded;
            this.proceed = proceed;
            this.armed.set(true);
        }

        void disarm() {
            armed.set(false);
        }

        void afterFindById(Long auctionId) {
            if (!armed.get() || auctionId == null || auctionId != targetAuctionId) {
                return;
            }
            loaded.countDown();
            try {
                boolean released = proceed.await(10, TimeUnit.SECONDS);
                if (!released) {
                    throw new IllegalStateException("ConflictGate: proceed latch timed out");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    static class CompetingBidExecutor {
        private final AuctionRepository auctionRepository;
        private final UserRepository userRepository;
        private final BidRepository bidRepository;

        CompetingBidExecutor(AuctionRepository auctionRepository, UserRepository userRepository, BidRepository bidRepository) {
            this.auctionRepository = auctionRepository;
            this.userRepository = userRepository;
            this.bidRepository = bidRepository;
        }

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        void placeCompetingBid(Long auctionId, Long bidderId, long amount) {
            Auction auction = auctionRepository.findById(auctionId).orElseThrow();
            User bidder = userRepository.findById(bidderId).orElseThrow();
            auction.placeManualBid(bidder, amount);
            auctionRepository.save(auction);
            bidRepository.save(Bid.place(auction, bidder, amount, BidType.MANUAL));
        }
    }

    // 연속된 bump가 매번 "직전 bump와 다른 bidder"가 되도록 여러 경쟁 bidder를 순환시킨다 -
    // 같은 bidder가 연속으로 다시 최고입찰자가 되려 하면 AlreadyHighestBidderException이 나서
    // conflict 유도 자체가 깨진다(§Auction.placeManualBid, 이미 최고입찰자인 재입찰 금지).
    static class ConflictHammer {
        private final CompetingBidExecutor competingBidExecutor;
        private volatile long targetAuctionId = -1;
        private volatile List<Long> competingBidderIds = List.of();
        private volatile long incrementStep = 5000;
        private final AtomicInteger remainingBumps = new AtomicInteger(0);
        private final AtomicInteger bumpIndex = new AtomicInteger(0);
        private final AtomicLong nextAmount = new AtomicLong();

        ConflictHammer(CompetingBidExecutor competingBidExecutor) {
            this.competingBidExecutor = competingBidExecutor;
        }

        void arm(long auctionId, List<Long> competingBidderIds, long startAmount, long incrementStep, int bumpCount) {
            this.targetAuctionId = auctionId;
            this.competingBidderIds = competingBidderIds;
            this.nextAmount.set(startAmount);
            this.incrementStep = incrementStep;
            this.bumpIndex.set(0);
            this.remainingBumps.set(bumpCount);
        }

        void disarm() {
            remainingBumps.set(0);
        }

        void bumpIfArmedAndTarget(Long auctionId) {
            if (auctionId == null || auctionId != targetAuctionId) {
                return;
            }
            int remainingBeforeThis = remainingBumps.getAndUpdate(n -> n > 0 ? n - 1 : 0);
            if (remainingBeforeThis <= 0) {
                return;
            }
            long amount = nextAmount.getAndAdd(incrementStep);
            int index = bumpIndex.getAndIncrement() % competingBidderIds.size();
            competingBidExecutor.placeCompetingBid(auctionId, competingBidderIds.get(index), amount);
        }
    }

    @TestConfiguration
    static class OptimisticExperimentConfig {

        @Bean
        ConflictGate conflictGate() {
            return new ConflictGate();
        }

        @Bean
        CompetingBidExecutor competingBidExecutor(
                @Qualifier("auctionRepository") AuctionRepository jpaAuctionRepository,
                UserRepository userRepository, BidRepository bidRepository
        ) {
            return new CompetingBidExecutor(jpaAuctionRepository, userRepository, bidRepository);
        }

        @Bean
        ConflictHammer conflictHammer(CompetingBidExecutor competingBidExecutor) {
            return new ConflictHammer(competingBidExecutor);
        }

        // production findByIdForUpdate()는 건드리지 않는다 - non-locking findById()만 감싼다.
        @Bean
        @Primary
        AuctionRepository conflictInjectingAuctionRepository(
                @Qualifier("auctionRepository") AuctionRepository jpaAuctionRepository,
                ConflictGate gate, ConflictHammer hammer
        ) {
            AuctionRepository proxy = Mockito.mock(
                    AuctionRepository.class, AdditionalAnswers.delegatesTo(jpaAuctionRepository)
            );
            Mockito.doAnswer(invocation -> {
                Long id = invocation.getArgument(0);
                Optional<Auction> result = jpaAuctionRepository.findById(id);
                gate.afterFindById(id);
                hammer.bumpIfArmedAndTarget(id);
                return result;
            }).when(proxy).findById(Mockito.anyLong());
            return proxy;
        }

        @Bean
        OptimisticBidAttemptService optimisticBidAttemptService(
                AuctionRepository auctionRepository, BidCommandService bidCommandService
        ) {
            return new OptimisticBidAttemptService(auctionRepository, bidCommandService);
        }

        @Bean
        OptimisticBidRetryOrchestrator optimisticBidRetryOrchestrator(
                ManualBidAttemptExecutor optimisticBidAttemptService
        ) {
            return new OptimisticBidRetryOrchestrator(optimisticBidAttemptService);
        }

        @Bean
        OptimisticManualBidService optimisticManualBidService(
                IdempotencyClaimService idempotencyClaimService, OptimisticBidRetryOrchestrator orchestrator
        ) {
            return new OptimisticManualBidService(idempotencyClaimService, orchestrator);
        }
    }

    private static final long START_PRICE = 10000;
    private static final long BID_INCREMENT = 5000;

    @Autowired
    private OptimisticManualBidService optimisticManualBidService;

    @Autowired
    private AuctionRepository auctionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private BidRepository bidRepository;

    @Autowired
    private IdempotencyRepository idempotencyRepository;

    @Autowired
    private AutoBidSettingRepository autoBidSettingRepository;

    @Autowired
    private ConflictGate conflictGate;

    @Autowired
    private ConflictHammer conflictHammer;

    @BeforeEach
    void resetGates() {
        conflictGate.disarm();
        conflictHammer.disarm();
    }

    private Auction persistLiveAuction() {
        User seller = userRepository.save(User.register(
                "opt-seller-" + UUID.randomUUID() + "@vintic.local", "seller", null
        ));
        Product product = productRepository.save(new Product(
                seller,
                List.of("https://example.com/a.jpg"),
                "Nike", "Dunk Low", "Panda", 270, "B", "PARTIAL",
                300000, 350000, "285,000~315,000", 290000, "사유", "설명"
        ));
        Auction auction = Auction.schedule(
                product, START_PRICE, BID_INCREMENT, LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1)
        );
        auction.start();
        return auctionRepository.save(auction);
    }

    private User persistUser(String label) {
        return userRepository.save(User.register(
                "opt-" + label + "-" + UUID.randomUUID() + "@vintic.local", label, null
        ));
    }

    private List<Bid> bidsFor(long auctionId) {
        return bidRepository.findByAuctionIdOrderByCreatedAtDescIdDesc(auctionId, Pageable.unpaged()).getContent();
    }

    private Optional<Idempotency> claimFor(String scope, Long userId, String key) {
        return idempotencyRepository.findByUserIdAndOperationScopeAndIdempotencyKey(userId, scope, key);
    }

    // ---- §1: 실제 MySQL optimistic conflict (genuine 2-thread concurrency) ----

    @Test
    void 실제_두_트랜잭션이_동시에_같은_Auction을_수정하면_conflict가_발생하고_retry가_최신_상태로_성공한다() throws Exception {
        Auction auction = persistLiveAuction();
        long auctionId = auction.getId();
        long bidderAId = persistUser("bidderA").getId();
        long bidderBId = persistUser("bidderB").getId();
        long amountB = START_PRICE + BID_INCREMENT; // 15000, B가 먼저 커밋
        long amountA = START_PRICE + 2 * BID_INCREMENT; // 20000, B 커밋 이후에도 유효/정렬됨
        String keyA = "optA-" + UUID.randomUUID();
        String keyB = "optB-" + UUID.randomUUID();

        CountDownLatch loaded = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        conflictGate.arm(auctionId, loaded, proceed);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<OptimisticBidOutcome> aFuture = executor.submit(
                    () -> optimisticManualBidService.placeBid(auctionId, bidderAId, amountA, keyA)
            );

            assertThat(loaded.await(10, TimeUnit.SECONDS)).as("A가 attempt1에서 이미 읽었어야 한다").isTrue();
            conflictGate.disarm(); // B/이후 A retry의 findById는 더 이상 gate에 걸리지 않게
            OptimisticBidOutcome bOutcome = optimisticManualBidService.placeBid(auctionId, bidderBId, amountB, keyB);
            proceed.countDown();

            OptimisticBidOutcome aOutcome = aFuture.get(30, TimeUnit.SECONDS);

            // 실제 conflict 발생 + retry가 정확히 새 attempt로 성공했는지
            assertThat(bOutcome.attemptsUsed()).isEqualTo(1);
            assertThat(bOutcome.conflictCount()).isEqualTo(0);
            assertThat(aOutcome.attemptsUsed()).isEqualTo(2);
            assertThat(aOutcome.conflictCount()).isEqualTo(1);
            assertThat(aOutcome.response().currentPrice()).isEqualTo(amountA);

            Auction reloaded = auctionRepository.findById(auctionId).orElseThrow();
            assertThat(reloaded.getCurrentPrice()).isEqualTo(amountA);
            assertThat(reloaded.getCurrentWinner().getId()).isEqualTo(bidderAId);
            // version=2: B의 성공 커밋(1) + A의 성공한 retry 커밋(1). A의 conflict난 attempt1은
            // rollback되어 version을 올리지 않는다.
            assertThat(reloaded.getVersion()).isEqualTo(2L);

            // duplicate Bid 없음 - A(1) + B(1) = 정확히 2건, A의 실패한 attempt1의 Bid는 없음.
            assertThat(bidsFor(auctionId)).hasSize(2);

            // claim은 logical request(2건)당 정확히 1건씩만 존재.
            assertThat(claimFor("PLACE_BID_OPTIMISTIC:" + auctionId, bidderAId, keyA)).isPresent();
            assertThat(claimFor("PLACE_BID_OPTIMISTIC:" + auctionId, bidderBId, keyB)).isPresent();
        } finally {
            executor.shutdown();
        }
    }

    // ---- §3: same-key replay, mismatch ----

    @Test
    void internal_retry_후_성공한_결과는_same_key_same_payload_replay_시_재실행_없이_그대로_반환된다() {
        Auction auction = persistLiveAuction();
        long auctionId = auction.getId();
        long bidderId = persistUser("bidder").getId();
        long amount = START_PRICE + BID_INCREMENT;
        String key = "replay-" + UUID.randomUUID();

        OptimisticBidOutcome first = optimisticManualBidService.placeBid(auctionId, bidderId, amount, key);
        int bidCountAfterFirst = bidsFor(auctionId).size();

        OptimisticBidOutcome replay = optimisticManualBidService.placeBid(auctionId, bidderId, amount, key);

        assertThat(replay).isEqualTo(first);
        assertThat(bidsFor(auctionId)).hasSize(bidCountAfterFirst);
        Auction reloaded = auctionRepository.findById(auctionId).orElseThrow();
        assertThat(reloaded.getCurrentPrice()).isEqualTo(amount);
    }

    @Test
    void same_key_different_payload는_IdempotencyPayloadMismatchException을_던지고_재실행되지_않는다() {
        Auction auction = persistLiveAuction();
        long auctionId = auction.getId();
        long bidderId = persistUser("bidder").getId();
        long amount = START_PRICE + BID_INCREMENT;
        long differentAmount = START_PRICE + 2 * BID_INCREMENT;
        String key = "mismatch-" + UUID.randomUUID();

        optimisticManualBidService.placeBid(auctionId, bidderId, amount, key);
        int bidCountAfterFirst = bidsFor(auctionId).size();

        assertThatThrownBy(() -> optimisticManualBidService.placeBid(auctionId, bidderId, differentAmount, key))
                .isInstanceOf(IdempotencyPayloadMismatchException.class); // GlobalExceptionHandler: 40905

        assertThat(bidsFor(auctionId)).hasSize(bidCountAfterFirst);
    }

    // ---- §3/§5/§6: exhaustion → claim rollback, 다음 동작과 모순 없음, partial state 없음 ----

    @Test
    void MAX_ATTEMPTS_소진시_claim이_rollback되고_같은_key로_이후_재시도가_정상_동작한다() {
        Auction auction = persistLiveAuction();
        long auctionId = auction.getId();
        // 연속 bump가 매번 직전 bump와 다른 bidder가 되도록 2명을 번갈아 쓴다(같은 bidder가
        // 연속으로 다시 최고입찰자가 되려 하면 AlreadyHighestBidderException).
        List<Long> hammerBidderIds = List.of(persistUser("hammer1").getId(), persistUser("hammer2").getId());
        long bidderAId = persistUser("bidderA").getId();
        // 5번 모두 conflict나면서도 매 attempt의 stale-read 시점 기준으로는 항상 유효/정렬되도록
        // (currentPrice + 6*increment): k번째 bump 이후 minNext = start+(k+1)*increment <= amountA.
        long amountA = START_PRICE + 6 * BID_INCREMENT;
        String key = "exhaust-" + UUID.randomUUID();

        conflictHammer.arm(auctionId, hammerBidderIds, START_PRICE + BID_INCREMENT, BID_INCREMENT,
                OptimisticBidRetryOrchestrator.MAX_ATTEMPTS);

        assertThatThrownBy(() -> optimisticManualBidService.placeBid(auctionId, bidderAId, amountA, key))
                .isInstanceOf(OptimisticRetryExhaustedException.class)
                .satisfies(e -> {
                    OptimisticRetryExhaustedException ex = (OptimisticRetryExhaustedException) e;
                    assertThat(ex.getAttemptsUsed()).isEqualTo(OptimisticBidRetryOrchestrator.MAX_ATTEMPTS);
                    assertThat(ex.getConflictCount()).isEqualTo(OptimisticBidRetryOrchestrator.MAX_ATTEMPTS);
                });

        // claim이 소진과 함께 rollback되어 남아있지 않아야 한다.
        assertThat(claimFor("PLACE_BID_OPTIMISTIC:" + auctionId, bidderAId, key)).isEmpty();
        // partial state 없음 - hammer가 만든 5건만 존재, A의 Bid는 하나도 없음.
        assertThat(bidsFor(auctionId)).hasSize(OptimisticBidRetryOrchestrator.MAX_ATTEMPTS);

        // 모순 없음 확인: 같은 key로 다시 시도하면(hammer는 이미 소진되어 더 안 터짐) 정상 성공해야 한다.
        OptimisticBidOutcome retryAfterExhaustion = optimisticManualBidService.placeBid(auctionId, bidderAId, amountA, key);
        assertThat(retryAfterExhaustion.attemptsUsed()).isEqualTo(1);
        assertThat(retryAfterExhaustion.conflictCount()).isEqualTo(0);
        assertThat(claimFor("PLACE_BID_OPTIMISTIC:" + auctionId, bidderAId, key)).isPresent();
        assertThat(bidsFor(auctionId)).hasSize(OptimisticBidRetryOrchestrator.MAX_ATTEMPTS + 1);
    }

    // ---- §5/§6: conflict 후 business rejection이면 exhaustion이 아니라 정상 거부 + partial 없음 ----

    @Test
    void conflict로_retry한_뒤_최신_상태_기준_business_rejection이면_exhaustion이_아니라_그대로_거부되고_partial_Bid가_없다() {
        Auction auction = persistLiveAuction();
        long auctionId = auction.getId();
        long hammerBidderId = persistUser("hammer").getId();
        long bidderAId = persistUser("bidderA").getId();
        long amountA = START_PRICE + 2 * BID_INCREMENT; // 20000: 최초 상태 기준으로는 유효
        long hammerAmount = 50000; // 한 번의 경쟁 커밋으로 A의 고정 amount를 무효화시킬 만큼 큰 값
        String key = "rejection-" + UUID.randomUUID();

        conflictHammer.arm(auctionId, List.of(hammerBidderId), hammerAmount, BID_INCREMENT, 1);

        assertThatThrownBy(() -> optimisticManualBidService.placeBid(auctionId, bidderAId, amountA, key))
                .isInstanceOf(BidAmountTooLowException.class);

        // exhaustion이 아니라 business rejection이므로 claim도 rollback(기존 규칙과 동일하게 재사용
        // 가능해야 한다) + Bid는 hammer의 1건만 존재해야 한다(partial 없음).
        assertThat(claimFor("PLACE_BID_OPTIMISTIC:" + auctionId, bidderAId, key)).isEmpty();
        assertThat(bidsFor(auctionId)).hasSize(1);
        Auction reloaded = auctionRepository.findById(auctionId).orElseThrow();
        assertThat(reloaded.getCurrentPrice()).isEqualTo(hammerAmount);
        assertThat(reloaded.getCurrentWinner().getId()).isEqualTo(hammerBidderId);
    }

    // ---- §4: retry가 실패 attempt의 AutoBid snapshot을 재사용하지 않고 최신 상태를 읽는지 ----

    @Test
    void retry는_실패한_attempt에서_변경된_적_없는_own_AutoBid_상태를_최신_DB_기준으로_다시_읽어_정확히_한_번_취소한다() {
        Auction auction = persistLiveAuction();
        long auctionId = auction.getId();
        User bidderA = persistUser("bidderA");
        AutoBidSetting ownSetting = AutoBidSetting.reserve(auction, bidderA, 100000L);
        ownSetting.activate();
        ownSetting = autoBidSettingRepository.save(ownSetting);
        long hammerBidderId = persistUser("hammer").getId();
        long amountA = START_PRICE + 2 * BID_INCREMENT; // 20000, 1회 hammer bump 이후에도 유효/정렬

        conflictHammer.arm(auctionId, List.of(hammerBidderId), START_PRICE + BID_INCREMENT, BID_INCREMENT, 1);

        OptimisticBidOutcome outcome = optimisticManualBidService.placeBid(
                auctionId, bidderA.getId(), amountA, "autobid-" + UUID.randomUUID()
        );

        assertThat(outcome.attemptsUsed()).isEqualTo(2);
        assertThat(outcome.conflictCount()).isEqualTo(1);
        assertThat(outcome.response().autoBidCanceled()).isTrue();

        AutoBidSetting reloaded = autoBidSettingRepository.findById(ownSetting.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AutoBidSettingStatus.CANCELED);
    }
}
