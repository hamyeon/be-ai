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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #33 no-lock 실험 baseline harness.
 *
 * 이 브랜치(setting/#33-concurrency-baseline)에서는 Auction.@Version이 그대로 남아있어,
 * 아래 파일럿 실행 시 낙관적 락 충돌(ObjectOptimisticLockingFailureException)이 발생하는 것이
 * 정상이다. 이번 harness의 목적은 "no-lock lost-update를 지금 재현하는 것"이 아니라
 * "harness 자체(동시 시작, race window delay, DB reset, post-state 검증)가 올바르게
 * 동작하는지"를 확인하는 것이다. 실제 no-lock 측정은 experiment/no-lock 브랜치에서
 * @Version을 제거한 뒤 이 harness를 그대로 재사용해 수행한다.
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

    // production AuctionRepository를 감싸 findById() 결과를 실제로 받은 "직후"에만
    // 지연시키는 test-only 대리 빈. @Primary로 이 테스트 컨텍스트에서만 실제 빈을 대체한다.
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
                Optional<Auction> result = jpaAuctionRepository.findById(id);
                raceWindowDelay.applyIfTarget(id);
                return result;
            }).when(proxy).findById(Mockito.anyLong());
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
                    actualMaxBidAmount, !violations.isEmpty(), violations, exceptionTypes, elapsedMillis
            );
        } catch (Exception e) {
            raceWindowDelay.disarm();
            throw new RuntimeException(e);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void 파일럿_동시_입찰_harness가_정상_동작한다() throws Exception {
        logEnvironment();

        List<WorkloadConfig> pilots = List.of(
                new WorkloadConfig(3, 200, 10000, 5000),
                new WorkloadConfig(5, 200, 10000, 5000),
                new WorkloadConfig(5, 400, 10000, 5000)
        );

        int runNumber = 1;
        for (WorkloadConfig config : pilots) {
            RunResult result = runOnce(runNumber, config);
            System.out.println("[pilot run=" + result.runNumber()
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

            // 이 브랜치는 @Version이 살아있어 실패(낙관적 락 충돌)가 나는 것이 정상이다.
            // harness가 검증할 것은 "성공한 요청 수만큼 Bid가 실제로 남고, 그 Bid들과
            // Auction 최종 상태가 서로 모순되지 않는가"이다.
            assertThat(result.violations()).as("run " + result.runNumber()).isEmpty();

            runNumber++;
        }
    }
}
