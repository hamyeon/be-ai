package com.vintic.backend.concurrency;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.bid.domain.Bid;
import com.vintic.backend.bid.dto.PlaceBidResponse;
import com.vintic.backend.bid.repository.BidRepository;
import com.vintic.backend.bid.service.ManualBidService;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
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
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * #33/#34 no-lock 실험과 #35 pessimistic lock 실험이 공유하는 concurrency harness.
 *
 * harness가 검증하는 것은 "요청이 실패했는가"가 아니라 "동시 실행 종료 후 DB post-state가
 * 실제 persisted Bid와 모순되지 않는가"(concurrency post-state invariant)이다.
 *
 * - no-lock 계열 테스트(#33/#34, 이 클래스의 기존 메서드)는 `experiment/no-lock`/
 *   `experiment/#34-no-lock` 브랜치에서 `Auction.@Version` 제거 + 일반 `findById()` 조회
 *   상태로 실행됐다. 그 raw 결과는 이 브랜치에서 재실행하지 않고 read-only 참고 자료로만
 *   취급한다.
 * - `experiment/#35-pessimistic-lock`에서는 production `BidCommandService`가
 *   `AuctionRepository.findByIdForUpdate()`(`PESSIMISTIC_WRITE`)를 쓰도록 바뀌었고,
 *   아래 {@code DelayConfig}도 그에 맞춰 `findByIdForUpdate()`를 감싼다 — 이 파일이 있는
 *   브랜치에서 no-lock 메서드를 다시 실행하면 실제로는 pessimistic lock 하에서 도는 것이니
 *   주의(§docs/experiments/concurrency/protocol.md).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Testcontainers
class ManualBidConcurrencyRaceIT {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        // 워커 수(파일럿 최대 10 내외)가 커넥션 풀에 막히지 않도록 넉넉히 고정한다.
        // 이 값 자체가 baseline 조건의 일부로 문서화된다(§concurrency-protocol.md).
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "20");
    }

    // production AuctionRepository를 감싸 findByIdForUpdate() 결과를 실제로 받은 "직후"에만
    // 지연시키는 test-only 대리 빈. @Primary로 이 테스트 컨텍스트에서만 실제 빈을 대체한다.
    // #35부터 production의 RMW 최초 조회가 findByIdForUpdate()(PESSIMISTIC_WRITE)로 바뀌어서
    // 여기도 그 메서드를 감싼다 — findById()를 감싸면 실제 RMW 경로에는 delay가 걸리지 않는다.
    @TestConfiguration
    static class DelayConfig {

        @Bean
        RaceWindowDelay raceWindowDelay() {
            return new RaceWindowDelay();
        }

        @Bean
        @Primary
        AuctionRepository delayingAuctionRepository(
                AuctionRepository jpaAuctionRepository, RaceWindowDelay raceWindowDelay
        ) {
            AuctionRepository proxy = Mockito.mock(
                    AuctionRepository.class, AdditionalAnswers.delegatesTo(jpaAuctionRepository)
            );
            Mockito.doAnswer(invocation -> {
                Long id = invocation.getArgument(0);
                Optional<Auction> result = jpaAuctionRepository.findByIdForUpdate(id);
                raceWindowDelay.applyIfTarget(id);
                return result;
            }).when(proxy).findByIdForUpdate(Mockito.anyLong());
            return proxy;
        }
    }

    @Autowired
    private ManualBidService manualBidService;

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
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    private record WorkloadConfig(int workerCount, long delayMillis, long startPrice, long bidIncrement) {
    }

    private record WorkerOutcome(Long bidderId, long amount, boolean success, String exceptionType) {
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
            List<String> exceptionTypes,
            long elapsedMillis
    ) {
    }

    private Auction persistLiveAuction(long startPrice, long bidIncrement) {
        User seller = userRepository.save(User.register(
                "race-seller-" + UUID.randomUUID() + "@vintic.local", "seller", null
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
        System.out.println("[env] mysql.version=" + mysqlVersion
                + " isolation=" + isolation
                + " hikari.maximumPoolSize=" + maxPoolSize
                + " springBootInstances=1");
    }

    private RunResult runOnce(int runNumber, WorkloadConfig config) throws InterruptedException {
        Auction auction = persistLiveAuction(config.startPrice(), config.bidIncrement());
        long auctionId = auction.getId();

        List<User> bidders = new ArrayList<>();
        for (int i = 0; i < config.workerCount(); i++) {
            bidders.add(userRepository.save(User.register(
                    "race-bidder-" + runNumber + "-" + i + "-" + UUID.randomUUID() + "@vintic.local",
                    "bidder" + i, null
            )));
        }

        // 각 워커는 minAmount(=startPrice+bidIncrement)를 공통 기준으로 서로 다른 유효 금액을 쓴다.
        // 순서상 마지막 워커가 가장 높은 금액이라, race가 없다면 마지막 워커가 최종 승자여야 한다.
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
                String idempotencyKey = "race-run-" + runNumber + "-" + i + "-" + UUID.randomUUID();

                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        manualBidService.placeBid(auctionId, bidderId, amount, idempotencyKey);
                        return new WorkerOutcome(bidderId, amount, true, null);
                    } catch (Exception e) {
                        return new WorkerOutcome(bidderId, amount, false, e.getClass().getSimpleName());
                    }
                }));
            }

            ready.await();
            start.countDown();

            List<WorkerOutcome> outcomes = new ArrayList<>();
            for (Future<WorkerOutcome> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
            long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

            raceWindowDelay.disarm();

            int successCount = (int) outcomes.stream().filter(WorkerOutcome::success).count();
            List<String> exceptionTypes = outcomes.stream()
                    .filter(o -> !o.success())
                    .map(WorkerOutcome::exceptionType)
                    .toList();

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
            if (successCount != bids.size()) {
                violations.add("SUCCESS_COUNT_MISMATCH: reported successes=" + successCount
                        + " persisted bids=" + bids.size());
            }

            return new RunResult(
                    runNumber, workerCount, successCount, workerCount - successCount,
                    reloaded.getCurrentPrice(),
                    reloaded.getCurrentWinner() != null ? reloaded.getCurrentWinner().getId() : null,
                    actualMaxBidAmount, actualMaxBidderId, bids.size(),
                    !violations.isEmpty(), violations, exceptionTypes, elapsedMillis
            );
        } catch (Exception e) {
            raceWindowDelay.disarm();
            throw new RuntimeException(e);
        } finally {
            executor.shutdown();
        }
    }

    // no-lock 탐색 단계 전용 테스트다. 목적이 "위반을 찾는 것" 자체라서 run마다
    // isEmpty()를 강제하면 첫 위반에서 나머지 파일럿을 못 돌린다. 그래서 여기서는
    // per-run 강제 assertion 없이 전체 결과를 로그로 남기고, 마지막에 요약만 출력한다.
    // (pessimistic-lock 등 이후 비교 실험에서 "위반이 없어야 함"을 다시 검증하고
    // 싶다면 그 브랜치에서 별도로 assertion을 추가하면 된다 — 이 harness 코드 자체는
    // 그대로 재사용 가능하다.)
    @Test
    void no_lock_상태에서_동시_입찰_race_조건을_탐색한다() throws Exception {
        logEnvironment();

        // 1차 탐색(3,200)/(3,500)/(8,500)/(8,1000)/(10,1000)에서 (8,1000)만 위반을 재현했다.
        // 재현성 확인을 위해 같은 조건을 5회 반복한다.
        List<WorkloadConfig> pilots = List.of(
                new WorkloadConfig(8, 1000, 10000, 5000),
                new WorkloadConfig(8, 1000, 10000, 5000),
                new WorkloadConfig(8, 1000, 10000, 5000),
                new WorkloadConfig(8, 1000, 10000, 5000),
                new WorkloadConfig(8, 1000, 10000, 5000)
        );

        int runNumber = 1;
        int violatedCount = 0;
        for (WorkloadConfig config : pilots) {
            RunResult result = runOnce(runNumber, config);
            System.out.println("[pilot run=" + result.runNumber()
                    + " workers=" + config.workerCount()
                    + " delayMs=" + config.delayMillis()
                    + "] requests=" + result.requestCount()
                    + " success=" + result.successCount()
                    + " failure=" + result.failureCount()
                    + " finalCurrentPrice=" + result.finalCurrentPrice()
                    + " finalWinnerId=" + result.finalWinnerId()
                    + " actualMaxBid=" + result.actualMaxBidAmount()
                    + " invariantViolated=" + result.invariantViolated()
                    + " violations=" + result.violations()
                    + " exceptions=" + result.exceptionTypes()
                    + " elapsedMs=" + result.elapsedMillis());

            if (result.invariantViolated()) {
                violatedCount++;
            }
            runNumber++;
        }
        System.out.println("[summary] " + violatedCount + "/" + pilots.size() + " runs violated invariants");
    }

    // #34 본 실험 raw data 저장 위치. Gradle test task의 working dir은 backend/ 모듈
    // 디렉터리라서 repo root 기준 상대 경로로 한 단계 올라간다(§concurrency/protocol.md
    // Data Storage 참고).
    private static final Path RAW_DIR = Path.of("..", "docs", "experiments", "concurrency", "raw");
    private static final Path MAIN_EXPERIMENT_CSV = RAW_DIR.resolve("no-lock-correctness.csv");
    private static final Path MAIN_EXPERIMENT_LOG_DIR = RAW_DIR.resolve("logs");
    private static final String CSV_HEADER = String.join(",",
            "run", "workerCount", "bidderCount", "delayMs", "initialPrice", "bidIncrement",
            "successCount", "failureCount", "cannotAcquireLockCount", "otherExceptionCount",
            "persistedBidCount", "currentPrice", "maxPersistedBid", "currentWinner", "maxBidBidder",
            "priceMismatch", "winnerMismatch", "successPersistedMismatch", "lostUpdate",
            "invariantViolation", "violations"
    );

    // #34 no-lock correctness 본 실험: #33에서 확정한 frozen 조건(§Frozen Main Experiment
    // Conditions)으로 동일 workload를 20회 반복한다. runOnce()/WorkloadConfig 등 파일럿과
    // 동일한 로직을 그대로 재사용하고, 이 메서드는 반복 횟수 고정(20)과 raw data 즉시
    // 저장(§Data Storage)만 담당한다.
    @Test
    void no_lock_상태에서_frozen_workload로_20회_본실험을_수행한다() throws Exception {
        logEnvironment();

        if (Files.exists(MAIN_EXPERIMENT_CSV)) {
            throw new IllegalStateException(
                    "본 실험 raw CSV가 이미 존재합니다(덮어쓰기 방지): "
                            + MAIN_EXPERIMENT_CSV.toAbsolutePath()
                            + " — 재측정하려면 기존 파일을 사람이 명시적으로 옮기거나 삭제해야 합니다."
            );
        }
        Files.createDirectories(MAIN_EXPERIMENT_LOG_DIR);
        writeLine(MAIN_EXPERIMENT_CSV, CSV_HEADER, false);

        WorkloadConfig frozen = new WorkloadConfig(8, 1000, 10000, 5000);
        int violatedRuns = 0;
        for (int runNumber = 1; runNumber <= 20; runNumber++) {
            RunResult result = runOnce(runNumber, frozen);

            appendCsvRow(frozen, result);
            writeRunLog(frozen, result);

            if (result.invariantViolated()) {
                violatedRuns++;
            }
            System.out.println("[main run=" + runNumber + "/20] success=" + result.successCount()
                    + " failure=" + result.failureCount()
                    + " invariantViolated=" + result.invariantViolated()
                    + " violations=" + result.violations());
        }
        System.out.println("[main summary] " + violatedRuns + "/20 runs violated invariants"
                + " (raw data: " + MAIN_EXPERIMENT_CSV.toAbsolutePath() + ")");
    }

    // #35 pessimistic lock raw data 저장 위치. no-lock(#34)과 같은 raw/logs 디렉터리를
    // 공유하고 파일명 prefix로만 구분한다(§concurrency/protocol.md Data Storage 참고).
    private static final Path PESSIMISTIC_EXPERIMENT_CSV = RAW_DIR.resolve("pessimistic-correctness.csv");
    private static final String PESSIMISTIC_CSV_HEADER = String.join(",",
            "run", "workerCount", "bidderCount", "delayMs", "initialPrice", "bidIncrement",
            "successCount", "failureCount", "businessRejectionCount", "cannotAcquireLockCount",
            "otherExceptionCount", "persistedBidCount", "currentPrice", "maxPersistedBid",
            "currentWinner", "maxBidBidder", "priceMismatch", "winnerMismatch",
            "successPersistedMismatch", "lostUpdate", "invariantViolation", "violations",
            "businessExceptionTypes"
    );
    // 기존 6개 business-rule regression 테스트(BidCommandServiceTest)가 검증하는 precondition/
    // error-rule 예외들. concurrency post-state invariant와는 별개 개념이라 여기서는 "정상
    // business validation으로 인한 request-level rejection" 분류에만 쓴다.
    private static final java.util.Set<String> BUSINESS_REJECTION_EXCEPTIONS = java.util.Set.of(
            "AuctionNotStartedException", "AuctionClosedException", "SellerCannotBidException",
            "AlreadyHighestBidderException", "BidAmountTooLowException", "PenaltyRestrictedException"
    );

    // #35 pessimistic write lock 본 실험: #34와 정확히 동일한 frozen 조건(workers=8,
    // bidders=8, delayMs=1000, startPrice=10000, bidIncrement=5000)으로 20회 반복한다.
    // runOnce()/WorkloadConfig/post-state checker는 no-lock과 완전히 동일하게 재사용하고,
    // 이 브랜치의 production 변경(AuctionRepository.findByIdForUpdate + PESSIMISTIC_WRITE)만
    // 독립변수로 검증한다. business validation rejection은 correctness violation도 DB lock
    // contention도 아니므로 별도 컬럼(businessRejectionCount)으로 분리해서 기록한다.
    @Test
    void pessimistic_write_lock_상태에서_frozen_workload로_20회_본실험을_수행한다() throws Exception {
        logEnvironment();

        if (Files.exists(PESSIMISTIC_EXPERIMENT_CSV)) {
            throw new IllegalStateException(
                    "본 실험 raw CSV가 이미 존재합니다(덮어쓰기 방지): "
                            + PESSIMISTIC_EXPERIMENT_CSV.toAbsolutePath()
                            + " — 재측정하려면 기존 파일을 사람이 명시적으로 옮기거나 삭제해야 합니다."
            );
        }
        Files.createDirectories(MAIN_EXPERIMENT_LOG_DIR);
        writeLine(PESSIMISTIC_EXPERIMENT_CSV, PESSIMISTIC_CSV_HEADER, false);

        WorkloadConfig frozen = new WorkloadConfig(8, 1000, 10000, 5000);
        int violatedRuns = 0;
        for (int runNumber = 1; runNumber <= 20; runNumber++) {
            RunResult result = runOnce(runNumber, frozen);

            appendPessimisticCsvRow(frozen, result);
            writePessimisticRunLog(frozen, result);

            if (result.invariantViolated()) {
                violatedRuns++;
            }
            System.out.println("[pessimistic run=" + runNumber + "/20] success=" + result.successCount()
                    + " failure=" + result.failureCount()
                    + " invariantViolated=" + result.invariantViolated()
                    + " violations=" + result.violations()
                    + " exceptions=" + result.exceptionTypes());
        }
        System.out.println("[pessimistic summary] " + violatedRuns + "/20 runs violated invariants"
                + " (raw data: " + PESSIMISTIC_EXPERIMENT_CSV.toAbsolutePath() + ")");
    }

    private long countCannotAcquireLock(List<String> exceptionTypes) {
        return exceptionTypes.stream().filter(t -> t.equals("CannotAcquireLockException")).count();
    }

    private boolean hasViolation(RunResult result, String prefix) {
        return result.violations().stream().anyMatch(v -> v.startsWith(prefix));
    }

    private void appendCsvRow(WorkloadConfig config, RunResult result) throws IOException {
        long cannotAcquireLockCount = countCannotAcquireLock(result.exceptionTypes());
        long otherExceptionCount = result.exceptionTypes().size() - cannotAcquireLockCount;

        String row = String.join(",",
                String.valueOf(result.runNumber()),
                String.valueOf(config.workerCount()),
                String.valueOf(config.workerCount()),
                String.valueOf(config.delayMillis()),
                String.valueOf(config.startPrice()),
                String.valueOf(config.bidIncrement()),
                String.valueOf(result.successCount()),
                String.valueOf(result.failureCount()),
                String.valueOf(cannotAcquireLockCount),
                String.valueOf(otherExceptionCount),
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
        writeLine(MAIN_EXPERIMENT_CSV, row, true);
    }

    private void writeRunLog(WorkloadConfig config, RunResult result) throws IOException {
        long cannotAcquireLockCount = countCannotAcquireLock(result.exceptionTypes());
        long otherExceptionCount = result.exceptionTypes().size() - cannotAcquireLockCount;
        String runId = String.format("%02d", result.runNumber());

        StringBuilder log = new StringBuilder();
        log.append("run=").append(result.runNumber()).append('\n');
        log.append("workerCount=").append(config.workerCount())
                .append(" bidderCount=").append(config.workerCount())
                .append(" delayMs=").append(config.delayMillis())
                .append(" initialPrice=").append(config.startPrice())
                .append(" bidIncrement=").append(config.bidIncrement()).append('\n');
        log.append("successCount=").append(result.successCount())
                .append(" failureCount=").append(result.failureCount())
                .append(" cannotAcquireLockCount=").append(cannotAcquireLockCount)
                .append(" otherExceptionCount=").append(otherExceptionCount).append('\n');
        log.append("exceptionTypes=").append(result.exceptionTypes()).append('\n');
        log.append("persistedBidCount=").append(result.persistedBidCount())
                .append(" actualMaxBidAmount=").append(result.actualMaxBidAmount())
                .append(" actualMaxBidderId=").append(result.actualMaxBidderId()).append('\n');
        log.append("finalCurrentPrice=").append(result.finalCurrentPrice())
                .append(" finalWinnerId=").append(result.finalWinnerId()).append('\n');
        log.append("invariantViolated=").append(result.invariantViolated()).append('\n');
        log.append("violations=").append(result.violations()).append('\n');
        log.append("elapsedMillis=").append(result.elapsedMillis()).append('\n');

        writeLine(MAIN_EXPERIMENT_LOG_DIR.resolve("no-lock-run-" + runId + ".log"), log.toString(), false);
    }

    private long countBusinessRejection(List<String> exceptionTypes) {
        return exceptionTypes.stream().filter(BUSINESS_REJECTION_EXCEPTIONS::contains).count();
    }

    private List<String> businessExceptionTypes(List<String> exceptionTypes) {
        return exceptionTypes.stream().filter(BUSINESS_REJECTION_EXCEPTIONS::contains).toList();
    }

    private void appendPessimisticCsvRow(WorkloadConfig config, RunResult result) throws IOException {
        long businessRejectionCount = countBusinessRejection(result.exceptionTypes());
        long cannotAcquireLockCount = countCannotAcquireLock(result.exceptionTypes());
        long otherExceptionCount = result.exceptionTypes().size() - businessRejectionCount - cannotAcquireLockCount;

        String row = String.join(",",
                String.valueOf(result.runNumber()),
                String.valueOf(config.workerCount()),
                String.valueOf(config.workerCount()),
                String.valueOf(config.delayMillis()),
                String.valueOf(config.startPrice()),
                String.valueOf(config.bidIncrement()),
                String.valueOf(result.successCount()),
                String.valueOf(result.failureCount()),
                String.valueOf(businessRejectionCount),
                String.valueOf(cannotAcquireLockCount),
                String.valueOf(otherExceptionCount),
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
                "\"" + String.join(";", result.violations()) + "\"",
                "\"" + String.join(";", businessExceptionTypes(result.exceptionTypes())) + "\""
        );
        writeLine(PESSIMISTIC_EXPERIMENT_CSV, row, true);
    }

    private void writePessimisticRunLog(WorkloadConfig config, RunResult result) throws IOException {
        long businessRejectionCount = countBusinessRejection(result.exceptionTypes());
        long cannotAcquireLockCount = countCannotAcquireLock(result.exceptionTypes());
        long otherExceptionCount = result.exceptionTypes().size() - businessRejectionCount - cannotAcquireLockCount;
        String runId = String.format("%02d", result.runNumber());

        StringBuilder log = new StringBuilder();
        log.append("run=").append(result.runNumber()).append('\n');
        log.append("workerCount=").append(config.workerCount())
                .append(" bidderCount=").append(config.workerCount())
                .append(" delayMs=").append(config.delayMillis())
                .append(" initialPrice=").append(config.startPrice())
                .append(" bidIncrement=").append(config.bidIncrement()).append('\n');
        log.append("successCount=").append(result.successCount())
                .append(" failureCount=").append(result.failureCount())
                .append(" businessRejectionCount=").append(businessRejectionCount)
                .append(" cannotAcquireLockCount=").append(cannotAcquireLockCount)
                .append(" otherExceptionCount=").append(otherExceptionCount).append('\n');
        log.append("exceptionTypes=").append(result.exceptionTypes()).append('\n');
        log.append("businessExceptionTypes=").append(businessExceptionTypes(result.exceptionTypes())).append('\n');
        log.append("persistedBidCount=").append(result.persistedBidCount())
                .append(" actualMaxBidAmount=").append(result.actualMaxBidAmount())
                .append(" actualMaxBidderId=").append(result.actualMaxBidderId()).append('\n');
        log.append("finalCurrentPrice=").append(result.finalCurrentPrice())
                .append(" finalWinnerId=").append(result.finalWinnerId()).append('\n');
        log.append("invariantViolated=").append(result.invariantViolated()).append('\n');
        log.append("violations=").append(result.violations()).append('\n');
        log.append("elapsedMillis=").append(result.elapsedMillis()).append('\n');

        writeLine(MAIN_EXPERIMENT_LOG_DIR.resolve("pessimistic-run-" + runId + ".log"), log.toString(), false);
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
}
