package com.vintic.backend.concurrency;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.bid.domain.Bid;
import com.vintic.backend.bid.repository.BidRepository;
import com.vintic.backend.bid.service.BidCommandService;
import com.vintic.backend.bid.service.IdempotencyClaimService;
import com.vintic.backend.bid.service.ManualBidAttemptExecutor;
import com.vintic.backend.bid.service.OptimisticBidAttemptService;
import com.vintic.backend.bid.service.OptimisticBidOutcome;
import com.vintic.backend.bid.service.OptimisticBidRetryOrchestrator;
import com.vintic.backend.bid.service.OptimisticManualBidService;
import com.vintic.backend.bid.service.OptimisticRetryExhaustedException;
import com.vintic.backend.bid.dto.PlaceBidResponse;
import com.vintic.backend.concurrency.support.RaceWindowDelay;
import com.vintic.backend.product.domain.Product;
import com.vintic.backend.product.repository.ProductRepository;
import com.vintic.backend.user.domain.User;
import com.vintic.backend.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;
import org.mockito.Mockito;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * #74-3 correctness 본실험 harness: #34(no-lock)/#35(pessimistic)와 정확히 동일한 frozen
 * workload(§docs/experiments/concurrency/protocol.md Frozen Main Experiment Conditions:
 * worker/bidder=8, delayMs=1000, initialPrice=10000, bidIncrement=5000, 20 runs, 동일 invariant
 * 기준)로 Optimistic Lock + bounded retry를 측정한다.
 *
 * <p>{@code ManualBidConcurrencyRaceIT}의 {@code runOnce()}/{@code WorkloadConfig}/synchronization/
 * invariant 로직을 구조적으로 그대로 재사용하고, Optimistic 전용 차이는 다음 3가지로 제한한다
 * (그 외 조건은 일절 변경하지 않는다):
 * <ul>
 *   <li>{@code Auction.@Version}(이미 experiment branch 전역에 적용됨)</li>
 *   <li>production 직접 호출 대신 {@link OptimisticManualBidService}(#74-2, bounded retry +
 *       기존 Idempotency claim 재사용) 호출</li>
 *   <li>retry instrumentation: {@link AttemptInstrumentation}이 요청(스레드)별 attempt/conflict
 *       횟수를 센다 - production/#74-1/#74-2 코드는 전혀 건드리지 않는 순수 test-only decorator다.</li>
 * </ul>
 *
 * <p><b>test-only race window 위치</b>: #35가 delay를 {@code findById()}에서
 * {@code findByIdForUpdate()}로 옮겼던 것과 동일한 원칙으로, 이번에는 Optimistic 경로의 최초
 * authoritative read인 {@code findById()}(non-locking)에 동일한 {@link RaceWindowDelay}
 * 메커니즘/값(1000ms)을 그대로 붙인다. 차이점: retry가 있는 전략이라 armed 상태인 동안은
 * "매 attempt의 findById() 호출마다" delay가 적용된다(최초 1회로 제한하는 별도 로직을 추가하지
 * 않았다 - 그런 특별 취급 자체가 "Optimistic에만 필요한 차이(@Version/retry/instrumentation)"
 * 3가지를 벗어나는 새로운 조건이 되기 때문). 이로 인해 회차가 겹치며 조건 충돌이 연쇄적으로
 * 발생할 수 있음을 완료보고에 그대로 기록한다(튜닝하지 않음).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Testcontainers
class OptimisticConcurrencyRaceIT {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "20");
    }

    // 요청(=worker thread)별 attempt/conflict 횟수를 관찰한다. ManualBidAttemptExecutor를
    // 감싸는 순수 decorator라 OptimisticBidRetryOrchestrator/OptimisticBidAttemptService의
    // retry 판단 로직은 전혀 건드리지 않는다 - business rejection으로 끝나는 request도
    // (exception에 attempt count가 실리지 않으므로) 이 방식으로만 attempt/conflict 수를 알 수 있다.
    static class AttemptInstrumentation {
        private final ThreadLocal<Integer> attempts = ThreadLocal.withInitial(() -> 0);
        private final ThreadLocal<Integer> conflicts = ThreadLocal.withInitial(() -> 0);

        void reset() {
            attempts.set(0);
            conflicts.set(0);
        }

        void recordAttempt() {
            attempts.set(attempts.get() + 1);
        }

        void recordConflict() {
            conflicts.set(conflicts.get() + 1);
        }

        int attempts() {
            return attempts.get();
        }

        int conflicts() {
            return conflicts.get();
        }
    }

    static class InstrumentedAttemptExecutor implements ManualBidAttemptExecutor {
        private final ManualBidAttemptExecutor delegate;
        private final AttemptInstrumentation instrumentation;

        InstrumentedAttemptExecutor(ManualBidAttemptExecutor delegate, AttemptInstrumentation instrumentation) {
            this.delegate = delegate;
            this.instrumentation = instrumentation;
        }

        @Override
        public PlaceBidResponse attempt(Long auctionId, Long userId, Long amount, Long idempotencyId) {
            instrumentation.recordAttempt();
            try {
                return delegate.attempt(auctionId, userId, amount, idempotencyId);
            } catch (ObjectOptimisticLockingFailureException e) {
                instrumentation.recordConflict();
                throw e;
            }
        }
    }

    @TestConfiguration
    static class OptimisticExperimentConfig {

        @Bean
        RaceWindowDelay raceWindowDelay() {
            return new RaceWindowDelay();
        }

        @Bean
        AttemptInstrumentation attemptInstrumentation() {
            return new AttemptInstrumentation();
        }

        // production findByIdForUpdate()는 건드리지 않는다 - Optimistic 경로가 실제로 쓰는
        // non-locking findById()만 감싼다(§35가 findByIdForUpdate()로 옮긴 것과 대칭).
        @Bean
        @Primary
        AuctionRepository delayingAuctionRepository(
                @Qualifier("auctionRepository") AuctionRepository jpaAuctionRepository, RaceWindowDelay raceWindowDelay
        ) {
            AuctionRepository proxy = Mockito.mock(
                    AuctionRepository.class, AdditionalAnswers.delegatesTo(jpaAuctionRepository)
            );
            Mockito.doAnswer(invocation -> {
                Long id = invocation.getArgument(0);
                Optional<Auction> result = jpaAuctionRepository.findById(id);
                raceWindowDelay.applyIfTarget(id);
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
        InstrumentedAttemptExecutor instrumentedAttemptExecutor(
                OptimisticBidAttemptService optimisticBidAttemptService, AttemptInstrumentation attemptInstrumentation
        ) {
            return new InstrumentedAttemptExecutor(optimisticBidAttemptService, attemptInstrumentation);
        }

        @Bean
        OptimisticBidRetryOrchestrator optimisticBidRetryOrchestrator(
                InstrumentedAttemptExecutor instrumentedAttemptExecutor
        ) {
            return new OptimisticBidRetryOrchestrator(instrumentedAttemptExecutor);
        }

        @Bean
        OptimisticManualBidService optimisticManualBidService(
                IdempotencyClaimService idempotencyClaimService, OptimisticBidRetryOrchestrator orchestrator
        ) {
            return new OptimisticManualBidService(idempotencyClaimService, orchestrator);
        }
    }

    @Autowired
    private OptimisticManualBidService optimisticManualBidService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private AuctionRepository auctionRepository;

    @Autowired
    private BidRepository bidRepository;

    @Autowired
    private RaceWindowDelay raceWindowDelay;

    @Autowired
    private AttemptInstrumentation attemptInstrumentation;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    private record WorkloadConfig(int workerCount, long delayMillis, long startPrice, long bidIncrement) {
    }

    private record WorkerOutcome(
            Long bidderId, long amount, boolean success, String exceptionType,
            int attemptsUsed, int conflictCount, boolean exhausted
    ) {
    }

    private record RunResult(
            int runNumber,
            int requestCount,
            int successCount,
            int failureCount,
            long finalCurrentPrice,
            Long finalWinnerId,
            long actualMaxBidAmount,
            Long actualMaxBidderId,
            int persistedBidCount,
            boolean invariantViolated,
            List<String> violations,
            List<WorkerOutcome> outcomes,
            long elapsedMillis
    ) {
    }

    private Auction persistLiveAuction(long startPrice, long bidIncrement) {
        User seller = userRepository.save(User.register(
                "opt-race-seller-" + UUID.randomUUID() + "@vintic.local", "seller", null
        ));
        Product product = productRepository.save(new Product(
                seller,
                List.of("https://example.com/a.jpg"),
                "Nike", "Dunk Low", "Panda", 270, "B", "PARTIAL",
                300000, 350000, "285,000~315,000", 290000, "사유", "설명"
        ));
        Auction auction = Auction.schedule(
                product, startPrice, bidIncrement, LocalDateTime.now().plusHours(1), LocalDateTime.now().plusHours(2)
        );
        auction.start();
        return auctionRepository.save(auction);
    }

    private void logEnvironment() {
        String mysqlVersion = jdbcTemplate.queryForObject("SELECT VERSION()", String.class);
        String isolation = jdbcTemplate.queryForObject("SELECT @@transaction_isolation", String.class);
        int maxPoolSize = -1;
        if (dataSource instanceof HikariDataSource hikari) {
            maxPoolSize = hikari.getMaximumPoolSize();
        }
        System.out.println("[opt-env] mysql.version=" + mysqlVersion
                + " isolation=" + isolation
                + " hikari.maximumPoolSize=" + maxPoolSize
                + " springBootInstances=1"
                + " maxAttempts=" + OptimisticBidRetryOrchestrator.MAX_ATTEMPTS
                + " backoff=none");
    }

    private RunResult runOnce(int runNumber, WorkloadConfig config) throws InterruptedException {
        Auction auction = persistLiveAuction(config.startPrice(), config.bidIncrement());
        long auctionId = auction.getId();

        List<User> bidders = new ArrayList<>();
        for (int i = 0; i < config.workerCount(); i++) {
            bidders.add(userRepository.save(User.register(
                    "opt-race-bidder-" + runNumber + "-" + i + "-" + UUID.randomUUID() + "@vintic.local",
                    "bidder" + i, null
            )));
        }

        long minAmount = config.startPrice() + config.bidIncrement();

        raceWindowDelay.configure(auctionId, config.delayMillis());
        raceWindowDelay.arm();

        int workerCount = config.workerCount();
        CountDownLatch ready = new CountDownLatch(workerCount);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        List<Future<WorkerOutcome>> futures = new ArrayList<>();

        long startedAt = System.nanoTime();
        try {
            for (int i = 0; i < workerCount; i++) {
                long bidderId = bidders.get(i).getId();
                long amount = minAmount + (long) i * config.bidIncrement();
                String idempotencyKey = "opt-race-" + runNumber + "-" + i + "-" + UUID.randomUUID();

                futures.add(executor.submit(() -> {
                    attemptInstrumentation.reset();
                    ready.countDown();
                    start.await();
                    try {
                        OptimisticBidOutcome outcome = optimisticManualBidService.placeBid(
                                auctionId, bidderId, amount, idempotencyKey
                        );
                        return new WorkerOutcome(
                                bidderId, amount, true, null,
                                outcome.attemptsUsed(), outcome.conflictCount(), false
                        );
                    } catch (Exception e) {
                        boolean exhausted = e instanceof OptimisticRetryExhaustedException;
                        return new WorkerOutcome(
                                bidderId, amount, false, e.getClass().getSimpleName(),
                                attemptInstrumentation.attempts(), attemptInstrumentation.conflicts(), exhausted
                        );
                    }
                }));
            }

            ready.await();
            start.countDown();

            List<WorkerOutcome> outcomes = new ArrayList<>();
            for (Future<WorkerOutcome> future : futures) {
                outcomes.add(future.get(60, TimeUnit.SECONDS));
            }
            long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

            raceWindowDelay.disarm();

            int successCount = (int) outcomes.stream().filter(WorkerOutcome::success).count();

            Auction reloaded = auctionRepository.findById(auctionId).orElseThrow();
            List<Bid> bids = bidRepository.findByAuctionIdOrderByCreatedAtDescIdDesc(
                    auctionId, Pageable.unpaged()
            ).getContent();
            long actualMaxBidAmount = bids.stream().mapToLong(Bid::getAmount).max().orElse(-1);
            Long actualMaxBidderId = bids.stream()
                    .max((a, b) -> Long.compare(a.getAmount(), b.getAmount()))
                    .map(b -> b.getUser().getId())
                    .orElse(null);

            List<String> violations = new ArrayList<>();
            if (!bids.isEmpty()) {
                if (reloaded.getCurrentPrice() != actualMaxBidAmount) {
                    violations.add("PRICE_MISMATCH: currentPrice=" + reloaded.getCurrentPrice()
                            + " actualMaxBid=" + actualMaxBidAmount);
                }
                Long currentWinnerId = reloaded.getCurrentWinner() != null ? reloaded.getCurrentWinner().getId() : null;
                if (!java.util.Objects.equals(currentWinnerId, actualMaxBidderId)) {
                    violations.add("WINNER_MISMATCH: currentWinner=" + currentWinnerId
                            + " actualMaxBidder=" + actualMaxBidderId);
                }
                boolean lostUpdate = bids.stream().anyMatch(b -> b.getAmount() > reloaded.getCurrentPrice());
                if (lostUpdate) {
                    violations.add("LOST_UPDATE: a Bid amount exceeds Auction.currentPrice");
                }
            }
            // internal retry로 인한 중복 Bid/partial state가 있다면 여기서 반드시 어긋난다 -
            // 성공 1건당 정확히 Bid 1건이어야 한다(§74-2에서 실측 확인한 원자성 그대로).
            if (successCount != bids.size()) {
                violations.add("SUCCESS_COUNT_MISMATCH: reported successes=" + successCount
                        + " persisted bids=" + bids.size());
            }

            return new RunResult(
                    runNumber, workerCount, successCount, workerCount - successCount,
                    reloaded.getCurrentPrice(),
                    reloaded.getCurrentWinner() != null ? reloaded.getCurrentWinner().getId() : null,
                    actualMaxBidAmount, actualMaxBidderId, bids.size(),
                    !violations.isEmpty(), violations, outcomes, elapsedMillis
            );
        } catch (Exception e) {
            raceWindowDelay.disarm();
            throw new RuntimeException(e);
        } finally {
            executor.shutdown();
        }
    }

    // §34/35와 동일 개념: precondition/error-rule 예외 6개는 concurrency violation이 아니라
    // "retry 후 최신 상태 기준 정상 business rejection"이다(§3, BID_AMOUNT_TOO_LOW 등이 달라지는
    // 것은 정상). BidNotAlignedException은 기존 no-lock/pessimistic harness의 집합에도 없다 -
    // 이 workload는 항상 정렬된 금액만 쓰므로 실제로 발생하지 않는다(기존과 동일하게 유지).
    private static final Set<String> BUSINESS_REJECTION_EXCEPTIONS = Set.of(
            "AuctionNotStartedException", "AuctionClosedException", "SellerCannotBidException",
            "AlreadyHighestBidderException", "BidAmountTooLowException", "PenaltyRestrictedException"
    );

    private static final Path RAW_DIR = Path.of("..", "docs", "experiments", "concurrency", "raw");
    private static final Path OPTIMISTIC_EXPERIMENT_CSV = RAW_DIR.resolve("optimistic-correctness.csv");
    private static final Path MAIN_EXPERIMENT_LOG_DIR = RAW_DIR.resolve("logs");
    private static final String CSV_HEADER = String.join(",",
            "run", "workerCount", "bidderCount", "delayMs", "initialPrice", "bidIncrement",
            "totalAttempts", "successCount", "businessRejectionCount", "exhaustedCount",
            "unexpectedDbFailureCount", "optimisticConflictCount", "totalRetries",
            "retryCountsPerRequest", "persistedBidCount", "currentPrice", "maxPersistedBid",
            "currentWinner", "maxBidBidder", "priceMismatch", "winnerMismatch",
            "successPersistedMismatch", "lostUpdate", "invariantViolation", "violations"
    );

    private boolean hasViolation(RunResult result, String prefix) {
        return result.violations().stream().anyMatch(v -> v.startsWith(prefix));
    }

    private long countByCategory(List<WorkerOutcome> outcomes, java.util.function.Predicate<WorkerOutcome> pred) {
        return outcomes.stream().filter(pred).count();
    }

    private void appendCsvRow(WorkloadConfig config, RunResult result) throws IOException {
        List<WorkerOutcome> outcomes = result.outcomes();
        long totalAttempts = outcomes.stream().mapToLong(WorkerOutcome::attemptsUsed).sum();
        long businessRejectionCount = countByCategory(outcomes,
                o -> !o.success() && BUSINESS_REJECTION_EXCEPTIONS.contains(o.exceptionType()));
        long exhaustedCount = countByCategory(outcomes, WorkerOutcome::exhausted);
        long unexpectedDbFailureCount = countByCategory(outcomes,
                o -> !o.success() && !o.exhausted() && !BUSINESS_REJECTION_EXCEPTIONS.contains(o.exceptionType()));
        long optimisticConflictCount = outcomes.stream().mapToLong(WorkerOutcome::conflictCount).sum();
        long totalRetries = outcomes.stream().mapToLong(o -> o.attemptsUsed() - 1).sum();
        String retryCountsPerRequest = outcomes.stream()
                .map(o -> String.valueOf(o.attemptsUsed() - 1))
                .reduce((a, b) -> a + ";" + b)
                .orElse("");

        String row = String.join(",",
                String.valueOf(result.runNumber()),
                String.valueOf(config.workerCount()),
                String.valueOf(config.workerCount()),
                String.valueOf(config.delayMillis()),
                String.valueOf(config.startPrice()),
                String.valueOf(config.bidIncrement()),
                String.valueOf(totalAttempts),
                String.valueOf(result.successCount()),
                String.valueOf(businessRejectionCount),
                String.valueOf(exhaustedCount),
                String.valueOf(unexpectedDbFailureCount),
                String.valueOf(optimisticConflictCount),
                String.valueOf(totalRetries),
                "\"" + retryCountsPerRequest + "\"",
                String.valueOf(result.persistedBidCount()),
                String.valueOf(result.finalCurrentPrice()),
                String.valueOf(result.actualMaxBidAmount()),
                String.valueOf(result.finalWinnerId()),
                String.valueOf(result.actualMaxBidderId()),
                String.valueOf(hasViolation(result, "PRICE_MISMATCH")),
                String.valueOf(hasViolation(result, "WINNER_MISMATCH")),
                String.valueOf(hasViolation(result, "SUCCESS_COUNT_MISMATCH")),
                String.valueOf(hasViolation(result, "LOST_UPDATE")),
                String.valueOf(result.invariantViolated()),
                "\"" + String.join(";", result.violations()) + "\""
        );
        writeLine(OPTIMISTIC_EXPERIMENT_CSV, row, true);
    }

    private void writeRunLog(WorkloadConfig config, RunResult result) throws IOException {
        List<WorkerOutcome> outcomes = result.outcomes();
        String runId = String.format("%02d", result.runNumber());

        StringBuilder log = new StringBuilder();
        log.append("run=").append(result.runNumber()).append('\n');
        log.append("workerCount=").append(config.workerCount())
                .append(" bidderCount=").append(config.workerCount())
                .append(" delayMs=").append(config.delayMillis())
                .append(" initialPrice=").append(config.startPrice())
                .append(" bidIncrement=").append(config.bidIncrement()).append('\n');
        log.append("successCount=").append(result.successCount())
                .append(" failureCount=").append(result.failureCount()).append('\n');
        for (WorkerOutcome o : outcomes) {
            log.append("  bidderId=").append(o.bidderId())
                    .append(" amount=").append(o.amount())
                    .append(" success=").append(o.success())
                    .append(" exceptionType=").append(o.exceptionType())
                    .append(" attemptsUsed=").append(o.attemptsUsed())
                    .append(" conflictCount=").append(o.conflictCount())
                    .append(" exhausted=").append(o.exhausted())
                    .append('\n');
        }
        log.append("persistedBidCount=").append(result.persistedBidCount())
                .append(" actualMaxBidAmount=").append(result.actualMaxBidAmount())
                .append(" actualMaxBidderId=").append(result.actualMaxBidderId()).append('\n');
        log.append("finalCurrentPrice=").append(result.finalCurrentPrice())
                .append(" finalWinnerId=").append(result.finalWinnerId()).append('\n');
        log.append("invariantViolated=").append(result.invariantViolated()).append('\n');
        log.append("violations=").append(result.violations()).append('\n');
        log.append("elapsedMillis=").append(result.elapsedMillis()).append('\n');

        writeLine(MAIN_EXPERIMENT_LOG_DIR.resolve("optimistic-run-" + runId + ".log"), log.toString(), false);
    }

    private void writeLine(Path path, String content, boolean append) throws IOException {
        StandardOpenOption[] options = append
                ? new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.APPEND}
                : new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING};
        try (BufferedWriter writer = Files.newBufferedWriter(path, options)) {
            writer.write(content);
            writer.newLine();
            writer.flush();
        }
    }

    // #74-3 correctness 본실험: #34/#35와 동일한 frozen 조건(worker/bidder=8, delayMs=1000,
    // initialPrice=10000, bidIncrement=5000)으로 20회 반복한다. Optimistic 전용 차이는
    // @Version/bounded retry/instrumentation 세 가지뿐이다(클래스 Javadoc 참고).
    @Test
    void optimistic_lock_retry_상태에서_frozen_workload로_20회_본실험을_수행한다() throws Exception {
        logEnvironment();

        if (Files.exists(OPTIMISTIC_EXPERIMENT_CSV)) {
            throw new IllegalStateException(
                    "본 실험 raw CSV가 이미 존재합니다(덮어쓰기 방지): "
                            + OPTIMISTIC_EXPERIMENT_CSV.toAbsolutePath()
                            + " — 재측정하려면 기존 파일을 사람이 명시적으로 옮기거나 삭제해야 합니다."
            );
        }
        Files.createDirectories(MAIN_EXPERIMENT_LOG_DIR);
        writeLine(OPTIMISTIC_EXPERIMENT_CSV, CSV_HEADER, false);

        WorkloadConfig frozen = new WorkloadConfig(8, 1000, 10000, 5000);
        int violatedRuns = 0;
        for (int runNumber = 1; runNumber <= 20; runNumber++) {
            RunResult result = runOnce(runNumber, frozen);

            appendCsvRow(frozen, result);
            writeRunLog(frozen, result);

            if (result.invariantViolated()) {
                violatedRuns++;
            }
            System.out.println("[optimistic main run=" + runNumber + "/20] success=" + result.successCount()
                    + " failure=" + result.failureCount()
                    + " invariantViolated=" + result.invariantViolated()
                    + " violations=" + result.violations());
        }
        System.out.println("[optimistic main summary] " + violatedRuns + "/20 runs violated invariants"
                + " (raw data: " + OPTIMISTIC_EXPERIMENT_CSV.toAbsolutePath() + ")");
    }
}
