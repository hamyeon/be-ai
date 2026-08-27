package com.vintic.backend.concurrency;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.bid.service.ManualBidService;
import com.vintic.backend.product.domain.Product;
import com.vintic.backend.product.repository.ProductRepository;
import com.vintic.backend.user.domain.User;
import com.vintic.backend.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * #36-A concurrency performance benchmark.
 *
 * correctness 실험(#34/#35, {@code ManualBidConcurrencyRaceIT})과 완전히 분리된 별도
 * harness다. correctness용 test-only delay({@code RaceWindowDelay})를 전혀 사용하지
 * 않는다 — {@code AuctionRepository}를 감싸는 Mockito proxy 자체가 없고, production
 * {@code ManualBidService}를 delay 없이 그대로 호출한다.
 *
 * 이 파일은 No-lock revision({@code exp/baseline-no-lock})과 Pessimistic revision
 * ({@code exp/pessimistic-lock})에 byte-for-byte 동일하게 적용한다 — repository 조회
 * 메서드 이름(findById vs findByIdForUpdate) 등 production 세부사항을 이 harness가
 * 전혀 알 필요가 없도록, AuctionRepository를 직접 감싸지 않고 ManualBidService만 호출한다.
 *
 * 출력 CSV 파일명은 시스템 프로퍼티/환경변수 {@code CONCURRENCY_PERFORMANCE_LABEL}
 * ("no-lock" 또는 "pessimistic")로 결정한다 — 이 값 하나만 revision마다 다르게 주고,
 * 소스 코드 자체는 두 revision에서 완전히 동일하게 유지한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Testcontainers
class ManualBidPerformanceBenchmarkIT {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        // correctness 실험(§concurrency/protocol.md)과 동일한 고정값.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "20");
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
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    private record WorkloadConfig(int concurrency, long startPrice, long bidIncrement) {
    }

    private record RequestRecord(
            int batch, int requestIndex, int concurrency, double latencyMs,
            String outcome, String exceptionType, double batchElapsedMs
    ) {
    }

    private static final Set<String> BUSINESS_REJECTION_EXCEPTIONS = Set.of(
            "AuctionNotStartedException", "AuctionClosedException", "SellerCannotBidException",
            "AlreadyHighestBidderException", "BidAmountTooLowException", "PenaltyRestrictedException"
    );

    private static final Set<String> CONCURRENCY_DB_FAILURE_EXCEPTIONS = Set.of(
            "CannotAcquireLockException"
    );

    private void logEnvironment() {
        String mysqlVersion = jdbcTemplate.queryForObject("SELECT VERSION()", String.class);
        String isolation = jdbcTemplate.queryForObject("SELECT @@transaction_isolation", String.class);
        int maxPoolSize = -1;
        if (dataSource instanceof HikariDataSource hikari) {
            maxPoolSize = hikari.getMaximumPoolSize();
        }
        System.out.println("[perf-env] mysql.version=" + mysqlVersion
                + " isolation=" + isolation
                + " hikari.maximumPoolSize=" + maxPoolSize
                + " springBootInstances=1");
    }

    private Auction persistLiveAuction(long startPrice, long bidIncrement) {
        User seller = userRepository.save(User.register(
                "perf-seller-" + UUID.randomUUID() + "@vintic.local", "seller", null
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

    private String classifyOutcome(String exceptionType) {
        if (exceptionType == null) {
            return "SUCCESS";
        }
        if (BUSINESS_REJECTION_EXCEPTIONS.contains(exceptionType)) {
            return "BUSINESS_REJECTION";
        }
        if (CONCURRENCY_DB_FAILURE_EXCEPTIONS.contains(exceptionType)) {
            return "CONCURRENCY_DB_FAILURE";
        }
        return "OTHER_FAILURE";
    }

    // 한 batch = concurrency개 concurrent request attempts. 이전 batch의 Auction/Bid 상태와
    // 무관하게 매번 새 Auction/Product/User를 만든다(§concurrency/protocol.md DB Reset Method와
    // 동일 원칙). DB reset/setup 시간은 batchElapsedMs 측정 구간 밖에 있다.
    private List<RequestRecord> runBatch(int batchNumber, WorkloadConfig config) throws InterruptedException {
        Auction auction = persistLiveAuction(config.startPrice(), config.bidIncrement());
        long auctionId = auction.getId();

        List<User> bidders = new ArrayList<>();
        for (int i = 0; i < config.concurrency(); i++) {
            bidders.add(userRepository.save(User.register(
                    "perf-bidder-" + batchNumber + "-" + i + "-" + UUID.randomUUID() + "@vintic.local",
                    "bidder" + i, null
            )));
        }

        long minAmount = config.startPrice() + config.bidIncrement();
        int concurrency = config.concurrency();
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        List<Future<double[]>> latencyFutures = new ArrayList<>();
        List<Future<String>> exceptionFutures = new ArrayList<>();

        try {
            List<Future<Object[]>> futures = new ArrayList<>();
            for (int i = 0; i < concurrency; i++) {
                long bidderId = bidders.get(i).getId();
                long amount = minAmount + (long) i * config.bidIncrement();
                String idempotencyKey = "perf-batch-" + batchNumber + "-" + i + "-" + UUID.randomUUID();

                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    long t0 = System.nanoTime();
                    String exceptionType = null;
                    try {
                        manualBidService.placeBid(auctionId, bidderId, amount, idempotencyKey);
                    } catch (Exception e) {
                        exceptionType = e.getClass().getSimpleName();
                    }
                    double latencyMs = (System.nanoTime() - t0) / 1_000_000.0;
                    return new Object[]{latencyMs, exceptionType};
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
                records.add(new RequestRecord(
                        batchNumber, i, concurrency, latencyMs,
                        classifyOutcome(exceptionType), exceptionType == null ? "" : exceptionType,
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
    private static final String CSV_HEADER =
            "batch,requestIndex,concurrency,latencyMs,outcome,exceptionType,batchElapsedMs";

    private String performanceLabel() {
        String label = System.getProperty("concurrency.performance.label");
        if (label == null) {
            label = System.getenv("CONCURRENCY_PERFORMANCE_LABEL");
        }
        if (label == null || label.isBlank()) {
            throw new IllegalStateException(
                    "CONCURRENCY_PERFORMANCE_LABEL(또는 -Dconcurrency.performance.label)이 설정되지 않았습니다. "
                            + "예: no-lock, pessimistic, smoketest"
            );
        }
        return label;
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

    private void appendBatch(Path csvPath, List<RequestRecord> records) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (RequestRecord r : records) {
            sb.append(String.join(",",
                    String.valueOf(r.batch()),
                    String.valueOf(r.requestIndex()),
                    String.valueOf(r.concurrency()),
                    String.format("%.3f", r.latencyMs()),
                    r.outcome(),
                    r.exceptionType(),
                    String.format("%.3f", r.batchElapsedMs())
            )).append('\n');
        }
        try (BufferedWriter writer = Files.newBufferedWriter(
                csvPath, StandardOpenOption.CREATE, StandardOpenOption.APPEND
        )) {
            writer.write(sb.toString());
            writer.flush();
        }
    }

    // #36-A 본 측정: correctness와 완전히 분리된 performance-only 실험이다. delay=0(전혀 없음),
    // warm-up 5 batch(폐기) + measurement 50 batch(기록) — §concurrency/protocol.md
    // Performance Measurement Protocol에 사전 확정된 값. 측정 도중 숫자를 보고 조건을
    // 바꾸지 않는다.
    @Test
    void concurrency_performance_benchmark를_수행한다() throws Exception {
        logEnvironment();

        String label = performanceLabel();
        Path csvPath = RAW_DIR.resolve(label + "-performance.csv");

        if (Files.exists(csvPath)) {
            throw new IllegalStateException(
                    "본 측정 raw CSV가 이미 존재합니다(덮어쓰기 방지): " + csvPath.toAbsolutePath()
                            + " — 재측정하려면 기존 파일을 사람이 명시적으로 옮기거나 삭제해야 합니다."
            );
        }
        Files.createDirectories(RAW_DIR);
        writeLine(csvPath, CSV_HEADER, false);

        WorkloadConfig config = new WorkloadConfig(8, 10000, 5000);

        for (int batch = 1; batch <= 5; batch++) {
            List<RequestRecord> records = runBatch(-batch, config);
            long success = records.stream().filter(r -> r.outcome().equals("SUCCESS")).count();
            System.out.println("[perf warmup batch=" + batch + "/5] success=" + success
                    + "/" + config.concurrency() + " (폐기, raw에 기록 안 함)");
        }

        for (int batch = 1; batch <= 50; batch++) {
            List<RequestRecord> records = runBatch(batch, config);
            appendBatch(csvPath, records);
            long success = records.stream().filter(r -> r.outcome().equals("SUCCESS")).count();
            double batchElapsedMs = records.get(0).batchElapsedMs();
            System.out.println("[perf measured batch=" + batch + "/50] success=" + success
                    + "/" + config.concurrency() + " batchElapsedMs=" + batchElapsedMs);
        }

        System.out.println("[perf summary] label=" + label
                + " measuredBatches=50 measuredAttempts=" + (50 * config.concurrency())
                + " (raw data: " + csvPath.toAbsolutePath() + ")");
    }
}
