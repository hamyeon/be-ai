package com.vintic.backend.auction.service;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.domain.AuctionStatus;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.bid.domain.Bid;
import com.vintic.backend.bid.domain.BidType;
import com.vintic.backend.bid.repository.BidRepository;
import com.vintic.backend.order.domain.Order;
import com.vintic.backend.order.repository.OrderRepository;
import com.vintic.backend.product.domain.Product;
import com.vintic.backend.product.repository.ProductRepository;
import com.vintic.backend.user.domain.User;
import com.vintic.backend.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

// Day12: AWS에서 API 서버 2대로 실험했을 때, 두 인스턴스의 AuctionEndScheduler가 같은 경매
// 후보를 동시에 종료 시도한 상황을 재현한다. runOnce()/EndRunSummary가 package-private이라
// AuctionEndAtomicityMySqlIT(concurrency 패키지)가 아니라 이 패키지에 둔다. 같은
// AuctionEndService/AuctionRepository/Clock을 공유하는 Scheduler 인스턴스 2개를 만들어
// runOnce()를 동시에 실행하고, 인스턴스별 집계가 아니라 "타이밍과 무관하게 항상 성립해야
// 하는" 총합 불변식만 검증한다 - 어느 인스턴스가 몇 건을 먼저 잡는지는 스케줄링에 달려 있어
// 고정할 수 없다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Testcontainers
class AuctionEndSchedulerMultiInstanceMySqlIT {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired
    private AuctionRepository auctionRepository;

    @Autowired
    private AuctionEndService auctionEndService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private BidRepository bidRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private Clock clock;

    @Test
    void 두_인스턴스가_동시에_runOnce해도_경매당_종료는_한_번만_반영되고_집계_합은_후보수와_같다() throws Exception {
        // fixture 시각은 LocalDateTime.now()가 아니라 주입받은 Clock 기준으로 만든다 - 운영
        // 컨테이너의 JVM 기본 시간대는 UTC이고 앱 Clock은 Asia/Seoul이라, JVM 시간대에
        // 의존하는 fixture는 실행 환경에 따라 마감 판정이 달라질 수 있다.
        LocalDateTime now = LocalDateTime.now(clock);

        User seller = userRepository.save(User.register("seller-" + System.nanoTime() + "@vintic.local", "seller", null));
        Product product = productRepository.save(new Product(
                seller,
                List.of("https://example.com/a.jpg"),
                "Nike", "Dunk Low", "Panda", 270, "B", "PARTIAL",
                300000, 350000, "285,000원 ~ 315,000원", 290000, "사유", "설명"
        ));

        List<User> winners = List.of(
                userRepository.save(User.register("winner1-" + System.nanoTime() + "@vintic.local", "winner1", null)),
                userRepository.save(User.register("winner2-" + System.nanoTime() + "@vintic.local", "winner2", null)),
                userRepository.save(User.register("winner3-" + System.nanoTime() + "@vintic.local", "winner3", null))
        );

        List<Auction> auctions = winners.stream()
                .map(winner -> {
                    Auction auction = Auction.schedule(
                            product, 10000L, 5000L, now.minusHours(2), now.minusMinutes(1)
                    );
                    auction.start();
                    Auction savedAuction = auctionRepository.save(auction);
                    bidRepository.save(Bid.place(savedAuction, winner, 30000L, BidType.MANUAL));
                    savedAuction.placeManualBid(winner, 30000L);
                    // @SpringBootTest에는 공유 트랜잭션이 없어 save()가 각자 즉시 commit되고
                    // 반환된 savedAuction은 detached다 - placeManualBid()로 바뀐
                    // currentWinner/currentPrice를 반영하려면 다시 save()해야 한다
                    // (AuctionEndAtomicityMySqlIT와 동일한 이유).
                    return auctionRepository.save(savedAuction);
                })
                .toList();

        AuctionEndScheduler schedulerA = new AuctionEndScheduler(auctionRepository, auctionEndService, clock, true, 100);
        AuctionEndScheduler schedulerB = new AuctionEndScheduler(auctionRepository, auctionEndService, clock, true, 100);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<AuctionEndScheduler.EndRunSummary> taskA = () -> {
            ready.countDown();
            start.await();
            return schedulerA.runOnce();
        };
        Callable<AuctionEndScheduler.EndRunSummary> taskB = () -> {
            ready.countDown();
            start.await();
            return schedulerB.runOnce();
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        AuctionEndScheduler.EndRunSummary summaryA;
        AuctionEndScheduler.EndRunSummary summaryB;
        try {
            Future<AuctionEndScheduler.EndRunSummary> futureA = executor.submit(taskA);
            Future<AuctionEndScheduler.EndRunSummary> futureB = executor.submit(taskB);
            ready.await();
            start.countDown();

            summaryA = futureA.get(30, TimeUnit.SECONDS);
            summaryB = futureB.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdown();
        }

        System.out.println("[multi-instance scheduler] A=" + summaryA + " B=" + summaryB);

        // 두 인스턴스가 후보를 언제 조회했는지에 따라 인스턴스별 값은 달라질 수 있으므로
        // 타이밍과 무관하게 항상 성립해야 하는 불변식만 검증한다.
        assertThat(summaryA.ended() + summaryB.ended()).isEqualTo(3);
        assertThat(summaryA.failed() + summaryB.failed()).isZero();
        assertThat(summaryA.ended() + summaryA.notLive() + summaryA.notDue() + summaryA.notFound())
                .isEqualTo(summaryA.candidates());
        assertThat(summaryB.ended() + summaryB.notLive() + summaryB.notDue() + summaryB.notFound())
                .isEqualTo(summaryB.candidates());

        for (int i = 0; i < auctions.size(); i++) {
            Auction reloaded = auctionRepository.findById(auctions.get(i).getId()).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(AuctionStatus.ENDED);

            List<Order> orders = orderRepository.findAll().stream()
                    .filter(o -> o.getAuction().getId().equals(reloaded.getId()))
                    .toList();
            assertThat(orders)
                    .as("경매 %d는 주문이 정확히 1건이어야 한다", reloaded.getId())
                    .hasSize(1);
            assertThat(orders.get(0).getBuyer().getId()).isEqualTo(winners.get(i).getId());
        }
    }
}
