package com.vintic.backend.concurrency;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.bid.dto.PlaceBidResponse;
import com.vintic.backend.bid.service.BidCommandService;
import com.vintic.backend.bid.service.IdempotencyClaimService;
import com.vintic.backend.bid.service.ManualBidAttemptExecutor;
import com.vintic.backend.bid.service.OptimisticBidAttemptService;
import com.vintic.backend.bid.service.OptimisticBidRetryOrchestrator;
import com.vintic.backend.bid.service.OptimisticManualBidService;
import com.vintic.backend.bid.service.OptimisticRetryExhaustedException;
import com.vintic.backend.product.domain.Product;
import com.vintic.backend.product.repository.ProductRepository;
import com.vintic.backend.user.domain.User;
import com.vintic.backend.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * #74-4A: {@code ManualBidPerformanceBenchmarkIT}(#36-A)와 비교 가능한 Optimistic Lock +
 * Retry performance harness. #36의 workload/측정 boundary/raw 관례를 그대로 재사용하고,
 * production {@code ManualBidService} 대신 {@link OptimisticManualBidService}(#74-1/#74-2,
 * 무수정)를 호출한다는 것과 attempt/conflict/retry instrumentation 컬럼이 추가된다는 것만
 * 다르다.
 *
 * <p>이 클래스는 {@code ManualBidPerformanceBenchmarkIT}(no-lock/pessimistic)를 전혀 수정하지
 * 않고 완전히 별도로 존재한다. correctness용 {@code RaceWindowDelay}는 사용하지 않는다(delay=0,
 * #36과 동일 원칙) - {@code AuctionRepository}를 감싸는 proxy 자체가 없다.
 *
 * <p><b>이 단계(#74-4A)에서는 본실험을 실행하지 않는다.</b> {@link #optimistic_concurrency_performance_benchmark를_수행한다()}
 * 는 raw schema/writer/overwrite guard까지 완성된 상태로 존재하지만, 사용자가 명시적으로
 * 실행을 지시하기 전까지 호출하지 않는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Testcontainers
class OptimisticConcurrencyPerformanceBenchmarkIT {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        // #36-A/correctness와 동일한 고정값.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "20");
    }

    // 요청(스레드)별 attempt/conflict 횟수 관찰 전용 - #74-3(OptimisticConcurrencyRaceIT)과
    // 동일한 목적/구조이지만, correctness harness 파일을 수정하지 않기 위해 이 클래스 안에
    // 독립적으로 다시 선언한다(같은 패턴의 의도적 소규모 중복 - orchestrator/attempt-service
    // 자체는 절대 복제하지 않는다). OptimisticBidRetryOrchestrator/OptimisticBidAttemptService의
    // retry 판단 로직은 전혀 건드리지 않는 순수 decorator다.
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
        AttemptInstrumentation attemptInstrumentation() {
            return new AttemptInstrumentation();
        }

        // #36-A 원칙 그대로: AuctionRepository를 감싸는 proxy 자체가 없다(delay=0, correctness와
        // 완전히 분리). Optimistic 경로는 production과 동일한 실제 AuctionRepository 빈을 그대로 쓴다.
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
    private AttemptInstrumentation attemptInstrumentation;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    private record WorkloadConfig(int concurrency, long startPrice, long bidIncrement) {
    }

    record RequestRecord(
            int batch, int requestIndex, int concurrency, double latencyMs,
            String outcome, String exceptionType,
            int attemptsUsed, int retryCount, int conflictCount, boolean exhausted,
            double batchElapsedMs
    ) {
    }

    // outcome 분류 4종(§3) - AutoBidSetting FOR UPDATE/InnoDB lock 실패(CannotAcquireLockException
    // 등)는 optimistic conflict가 아니다. optimistic conflict는 오직
    // ObjectOptimisticLockingFailureException(attemptsUsed/conflictCount instrumentation)로만
    // 관찰하고, 여기 classifyOutcome()의 "예외 종류" 분류와는 별개 차원이다 - UNEXPECTED_DB_FAILURE로
    // 떨어지는 실패도 conflictCount>0일 수 있고(§#74-3 run-15처럼 conflict 후 다른 예외로 끝나는
    // 경우), 이 두 값을 혼동해서 집계하지 않는다.
    static final Set<String> BUSINESS_REJECTION_EXCEPTIONS = Set.of(
            "AuctionNotStartedException", "AuctionClosedException", "SellerCannotBidException",
            "AlreadyHighestBidderException", "BidAmountTooLowException", "PenaltyRestrictedException"
    );

    static String classifyOutcome(String exceptionType) {
        if (exceptionType == null) {
            return "SUCCESS";
        }
        if (exceptionType.equals("OptimisticRetryExhaustedException")) {
            return "OPTIMISTIC_RETRY_EXHAUSTED";
        }
        if (BUSINESS_REJECTION_EXCEPTIONS.contains(exceptionType)) {
            return "BUSINESS_REJECTION";
        }
        return "UNEXPECTED_DB_FAILURE";
    }

    private void logEnvironment() {
        String mysqlVersion = jdbcTemplate.queryForObject("SELECT VERSION()", String.class);
        String isolation = jdbcTemplate.queryForObject("SELECT @@transaction_isolation", String.class);
        int maxPoolSize = -1;
        if (dataSource instanceof HikariDataSource hikari) {
            maxPoolSize = hikari.getMaximumPoolSize();
        }
        System.out.println("[opt-perf-env] mysql.version=" + mysqlVersion
                + " isolation=" + isolation
                + " hikari.maximumPoolSize=" + maxPoolSize
                + " springBootInstances=1"
                + " maxAttempts=" + OptimisticBidRetryOrchestrator.MAX_ATTEMPTS
                + " backoff=none");
    }

    private Auction persistLiveAuction(long startPrice, long bidIncrement) {
        User seller = userRepository.save(User.register(
                "opt-perf-seller-" + UUID.randomUUID() + "@vintic.local", "seller", null
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

    // 한 batch = concurrency개 concurrent request attempts. §36-A와 동일한 batch reset 원칙
    // (매번 새 Auction/Product/User, DB reset/setup은 측정 구간 밖) + 측정 boundary
    // (OptimisticManualBidService.placeBid() 호출 시작~반환, service-level latency) 동일.
    private List<RequestRecord> runBatch(int batchNumber, WorkloadConfig config) throws InterruptedException {
        Auction auction = persistLiveAuction(config.startPrice(), config.bidIncrement());
        long auctionId = auction.getId();

        List<User> bidders = new ArrayList<>();
        for (int i = 0; i < config.concurrency(); i++) {
            bidders.add(userRepository.save(User.register(
                    "opt-perf-bidder-" + batchNumber + "-" + i + "-" + UUID.randomUUID() + "@vintic.local",
                    "bidder" + i, null
            )));
        }

        long minAmount = config.startPrice() + config.bidIncrement();
        int concurrency = config.concurrency();
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);

        try {
            List<Future<Object[]>> futures = new ArrayList<>();
            for (int i = 0; i < concurrency; i++) {
                long bidderId = bidders.get(i).getId();
                long amount = minAmount + (long) i * config.bidIncrement();
                String idempotencyKey = "opt-perf-batch-" + batchNumber + "-" + i + "-" + UUID.randomUUID();

                futures.add(executor.submit(() -> {
                    attemptInstrumentation.reset();
                    ready.countDown();
                    start.await();
                    long t0 = System.nanoTime();
                    String exceptionType = null;
                    boolean exhausted = false;
                    try {
                        optimisticManualBidService.placeBid(auctionId, bidderId, amount, idempotencyKey);
                    } catch (Exception e) {
                        exceptionType = e.getClass().getSimpleName();
                        exhausted = e instanceof OptimisticRetryExhaustedException;
                    }
                    double latencyMs = (System.nanoTime() - t0) / 1_000_000.0;
                    // attemptsUsed/conflictCount(§2 고정 정의): 실제 실행한 transaction attempt 수 /
                    // 실제 ObjectOptimisticLockingFailureException 발생 횟수 - 성공/실패 모두 이
                    // instrumentation 값 하나로 통일한다(성공 시 OptimisticBidOutcome과 동일한 값).
                    int attemptsUsed = attemptInstrumentation.attempts();
                    int conflictCount = attemptInstrumentation.conflicts();
                    return new Object[]{latencyMs, exceptionType, attemptsUsed, conflictCount, exhausted};
                }));
            }

            ready.await();
            long batchStartedAt = System.nanoTime();
            start.countDown();

            List<Object[]> outcomes = new ArrayList<>();
            for (Future<Object[]> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
            double batchElapsedMs = (System.nanoTime() - batchStartedAt) / 1_000_000.0;

            List<RequestRecord> records = new ArrayList<>();
            for (int i = 0; i < outcomes.size(); i++) {
                double latencyMs = (double) outcomes.get(i)[0];
                String exceptionType = (String) outcomes.get(i)[1];
                int attemptsUsed = (int) outcomes.get(i)[2];
                int conflictCount = (int) outcomes.get(i)[3];
                boolean exhausted = (boolean) outcomes.get(i)[4];
                records.add(new RequestRecord(
                        batchNumber, i, concurrency, latencyMs,
                        classifyOutcome(exceptionType), exceptionType == null ? "" : exceptionType,
                        attemptsUsed, attemptsUsed - 1, conflictCount, exhausted,
                        batchElapsedMs
                ));
            }
            return records;
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            executor.shutdown();
        }
    }

    private static final Path RAW_DIR = Path.of("..", "docs", "experiments", "concurrency", "raw");
    private static final Path OPTIMISTIC_PERFORMANCE_CSV = RAW_DIR.resolve("optimistic-performance.csv");
    static final String CSV_HEADER = String.join(",",
            "batch", "requestIndex", "concurrency", "latencyMs", "outcome", "exceptionType",
            "attemptsUsed", "retryCount", "conflictCount", "exhausted", "batchElapsedMs"
    );

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

    static String formatCsvRow(RequestRecord r) {
        return String.join(",",
                String.valueOf(r.batch()),
                String.valueOf(r.requestIndex()),
                String.valueOf(r.concurrency()),
                String.format("%.3f", r.latencyMs()),
                r.outcome(),
                r.exceptionType(),
                String.valueOf(r.attemptsUsed()),
                String.valueOf(r.retryCount()),
                String.valueOf(r.conflictCount()),
                String.valueOf(r.exhausted()),
                String.format("%.3f", r.batchElapsedMs())
        );
    }

    private void appendBatch(Path csvPath, List<RequestRecord> records) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (RequestRecord r : records) {
            sb.append(formatCsvRow(r)).append('\n');
        }
        try (BufferedWriter writer = Files.newBufferedWriter(
                csvPath, StandardOpenOption.CREATE, StandardOpenOption.APPEND
        )) {
            writer.write(sb.toString());
            writer.flush();
        }
    }

    // #74-4B 본 측정 예정 메서드. #36-A와 동일한 workload(concurrency=8, warm-up 5 batch 폐기 +
    // measurement 50 batch 기록, delay=0, initialPrice=10000, bidIncrement=5000)로 실행한다.
    // #74-4A(이 단계)에서는 호출하지 않는다 - schema/writer/overwrite guard만 준비된 상태로 둔다.
    @Test
    void optimistic_concurrency_performance_benchmark를_수행한다() throws Exception {
        logEnvironment();

        if (Files.exists(OPTIMISTIC_PERFORMANCE_CSV)) {
            throw new IllegalStateException(
                    "본 측정 raw CSV가 이미 존재합니다(덮어쓰기 방지): " + OPTIMISTIC_PERFORMANCE_CSV.toAbsolutePath()
                            + " — 재측정하려면 기존 파일을 사람이 명시적으로 옮기거나 삭제해야 합니다."
            );
        }
        Files.createDirectories(RAW_DIR);
        writeLine(OPTIMISTIC_PERFORMANCE_CSV, CSV_HEADER, false);

        WorkloadConfig config = new WorkloadConfig(8, 10000, 5000);

        for (int batch = 1; batch <= 5; batch++) {
            List<RequestRecord> records = runBatch(-batch, config);
            long success = records.stream().filter(r -> r.outcome().equals("SUCCESS")).count();
            System.out.println("[opt-perf warmup batch=" + batch + "/5] success=" + success
                    + "/" + config.concurrency() + " (폐기, raw에 기록 안 함)");
        }

        for (int batch = 1; batch <= 50; batch++) {
            List<RequestRecord> records = runBatch(batch, config);
            appendBatch(OPTIMISTIC_PERFORMANCE_CSV, records);
            long success = records.stream().filter(r -> r.outcome().equals("SUCCESS")).count();
            double batchElapsedMs = records.get(0).batchElapsedMs();
            System.out.println("[opt-perf measured batch=" + batch + "/50] success=" + success
                    + "/" + config.concurrency() + " batchElapsedMs=" + batchElapsedMs);
        }

        System.out.println("[opt-perf summary] measuredBatches=50 measuredAttempts=" + (50 * config.concurrency())
                + " (raw data: " + OPTIMISTIC_PERFORMANCE_CSV.toAbsolutePath() + ")");
    }
}
