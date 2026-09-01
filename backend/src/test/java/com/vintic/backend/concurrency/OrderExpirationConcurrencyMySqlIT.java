package com.vintic.backend.concurrency;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.backupoffer.repository.BackupOfferRepository;
import com.vintic.backend.bid.domain.Bid;
import com.vintic.backend.bid.domain.BidType;
import com.vintic.backend.bid.repository.BidRepository;
import com.vintic.backend.order.domain.Order;
import com.vintic.backend.order.domain.OrderStatus;
import com.vintic.backend.order.repository.OrderRepository;
import com.vintic.backend.order.service.OrderExpirationService;
import com.vintic.backend.penalty.repository.PenaltyRepository;
import com.vintic.backend.product.domain.Product;
import com.vintic.backend.product.repository.ProductRepository;
import com.vintic.backend.user.domain.User;
import com.vintic.backend.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// #57-2: OrderExpirationService.expireIfDue()의 (a) 동시 실행 correctness - Auction/Order FOR
// UPDATE가 두 호출을 직렬화해 penalty/BackupOffer가 정확히 1건씩만 생기는지, (b) 트랜잭션
// 원자성 - penalties INSERT가 실패하면 Order 전이/User 갱신까지 전부 롤백되는지를 실제 MySQL로
// 검증한다. #45/#56-2/#56-3이 확립한 방식(CountDownLatch 동시 실행, 임시 CHECK 제약으로 강제
// 실패)을 그대로 재사용한다 - 이 서비스에는 HTTP endpoint가 없으므로(강제 만료용 API를 만들지
// 않는다는 계약) 서비스 메서드를 직접 두 스레드에서 호출한다.
//
// payment.expiration.enabled/backup-offer.expiration.enabled를 꺼서 실제 @Scheduled 배치가
// 이 테스트의 직접 호출과 경합하지 않게 한다 - 이 두 스케줄러는 매 분 정각에 돈다
// (application.yml의 cron 기본값), 테스트 실행 시각이 우연히 그 시점과 겹치면 이 테스트가
// 만든 fixture를 스케줄러가 먼저 처리해버려 assertion이 흔들릴 수 있다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Testcontainers
class OrderExpirationConcurrencyMySqlIT {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("payment.expiration.enabled", () -> "false");
        registry.add("backup-offer.expiration.enabled", () -> "false");
    }

    @Autowired
    private OrderExpirationService orderExpirationService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private AuctionRepository auctionRepository;

    @Autowired
    private BidRepository bidRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PenaltyRepository penaltyRepository;

    @Autowired
    private BackupOfferRepository backupOfferRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private record Fixture(Auction auction, User winner, User rank2, Order order) {
    }

    private Fixture persistPastDueWinnerOrder() {
        User seller = userRepository.save(User.register("seller-" + System.nanoTime() + "@vintic.local", "seller", null));
        User winner = userRepository.save(User.register("winner-" + System.nanoTime() + "@vintic.local", "winner", null));
        User rank2 = userRepository.save(User.register("rank2-" + System.nanoTime() + "@vintic.local", "rank2", null));
        Product product = productRepository.save(new Product(
                seller,
                List.of("https://example.com/a.jpg"),
                "Nike", "Dunk Low", "Panda", 270, "B", "PARTIAL",
                300000, 350000, "285,000원 ~ 315,000원", 290000, "사유", "설명"
        ));
        Auction auction = Auction.schedule(
                product, 10000L, 5000L, LocalDateTime.now().minusHours(2), LocalDateTime.now().minusHours(1)
        );
        auction.start();
        Auction savedAuction = auctionRepository.save(auction);
        bidRepository.save(Bid.place(savedAuction, rank2, 20000L, BidType.MANUAL));
        savedAuction.placeManualBid(rank2, 20000L);
        bidRepository.save(Bid.place(savedAuction, winner, 25000L, BidType.MANUAL));
        savedAuction.placeManualBid(winner, 25000L);
        savedAuction.end();
        Order order = orderRepository.save(Order.createForWinner(
                savedAuction, winner, 25000L, 3000L, LocalDateTime.now().minusMinutes(1)
        ));
        return new Fixture(savedAuction, winner, rank2, order);
    }

    @Test
    void 동시에_같은_Order를_만료처리해도_penalty와_BackupOffer는_1건만_생성된다() throws Exception {
        Fixture fixture = persistPastDueWinnerOrder();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Void> task = () -> {
            ready.countDown();
            start.await();
            orderExpirationService.expireIfDue(fixture.order().getId());
            return null;
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Void> futureA = executor.submit(task);
            Future<Void> futureB = executor.submit(task);
            ready.await();
            start.countDown();

            futureA.get(30, TimeUnit.SECONDS);
            futureB.get(30, TimeUnit.SECONDS);

            Order reloaded = orderRepository.findById(fixture.order().getId()).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.PAYMENT_EXPIRED);
            assertThat(penaltyRepository.count()).isEqualTo(1);
            assertThat(backupOfferRepository.count()).isEqualTo(1);

            User reloadedWinner = userRepository.findById(fixture.winner().getId()).orElseThrow();
            assertThat(reloadedWinner.getNoshowCount()).isEqualTo(1);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void penalties_INSERT가_실패하면_Order_전이와_User_갱신도_모두_롤백된다() {
        Fixture fixture = persistPastDueWinnerOrder();
        // 같은 클래스의 다른 테스트(동시_실행 테스트)가 먼저 실행돼 penalties/backup_offers에
        // 남긴 row가 있으면 (a) MySQL이 ADD CONSTRAINT 시점에 기존 penalties row까지 검증해
        // ALTER 자체가 실패하고, (b) 이 테스트 자체와 무관한 backup_offers row 때문에 아래
        // count()==0 단언이 흔들린다 - 이 테스트만의 관심사와 무관한 순서 의존성을 없애기 위해
        // 시작 전에 두 테이블을 비운다.
        jdbcTemplate.execute("DELETE FROM backup_offers");
        jdbcTemplate.execute("DELETE FROM penalties");
        jdbcTemplate.execute("ALTER TABLE penalties ADD CONSTRAINT chk_smoke_force_penalty_fail CHECK (1 = 0)");
        try {

            assertThatThrownBy(() -> orderExpirationService.expireIfDue(fixture.order().getId()))
                    .isInstanceOf(RuntimeException.class);

            Order reloaded = orderRepository.findById(fixture.order().getId()).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(penaltyRepository.count()).isZero();
            assertThat(backupOfferRepository.count()).isZero();

            User reloadedWinner = userRepository.findById(fixture.winner().getId()).orElseThrow();
            assertThat(reloadedWinner.getNoshowCount()).isZero();
            assertThat(reloadedWinner.getBidRestrictedUntil()).isNull();
        } finally {
            jdbcTemplate.execute("ALTER TABLE penalties DROP CHECK chk_smoke_force_penalty_fail");
        }
    }
}
