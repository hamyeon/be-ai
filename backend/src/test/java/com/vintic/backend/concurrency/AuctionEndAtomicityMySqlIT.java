package com.vintic.backend.concurrency;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.domain.AuctionStatus;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.auction.service.AuctionEndService;
import com.vintic.backend.bid.domain.Bid;
import com.vintic.backend.bid.domain.BidType;
import com.vintic.backend.bid.repository.BidRepository;
import com.vintic.backend.bid.service.BidCommandService;
import com.vintic.backend.common.exception.AuctionClosedException;
import com.vintic.backend.order.domain.Order;
import com.vintic.backend.order.repository.OrderRepository;
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

// #73-2/#73-4: AuctionEndService.endIfDue()가 Auction ENDED 전환 + #56 AuctionSettlementService.
// settle()(winner Order 생성)을 하나의 트랜잭션으로 묶는지, 그리고 동일 Auction에 대한 실제 동시
// invocation에도 한 번만 반영되는지 실제 MySQL로 검증한다. rollback 케이스(#73-1과 동일한 임시
// CHECK 제약 방식)와 CountDownLatch 2-thread 동시 실행 케이스(#57-2와 동일 harness) 둘 다 이
// 클래스 하나에 둔다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Testcontainers
class AuctionEndAtomicityMySqlIT {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired
    private AuctionEndService auctionEndService;

    @Autowired
    private BidCommandService bidCommandService;

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
    private JdbcTemplate jdbcTemplate;

    @Test
    void Order_INSERT가_실패하면_Auction_ENDED_전환도_함께_롤백된다() {
        jdbcTemplate.execute("ALTER TABLE orders ADD CONSTRAINT chk_smoke_force_order_fail CHECK (1 = 0)");
        try {
            User seller = userRepository.save(User.register("seller-" + System.nanoTime() + "@vintic.local", "seller", null));
            User winner = userRepository.save(User.register("winner-" + System.nanoTime() + "@vintic.local", "winner", null));
            Product product = productRepository.save(new Product(
                    seller,
                    List.of("https://example.com/a.jpg"),
                    "Nike", "Dunk Low", "Panda", 270, "B", "PARTIAL",
                    300000, 350000, "285,000원 ~ 315,000원", 290000, "사유", "설명"
            ));
            Auction auction = Auction.schedule(
                    product, 10000L, 5000L, LocalDateTime.now().minusHours(2), LocalDateTime.now().minusMinutes(1)
            );
            auction.start();
            Auction savedAuction = auctionRepository.save(auction);
            bidRepository.save(Bid.place(savedAuction, winner, 30000L, BidType.MANUAL));
            savedAuction.placeManualBid(winner, 30000L);
            // @SpringBootTest에는 테스트를 감싸는 공유 트랜잭션이 없다 - 위 save() 호출은 각자
            // 자기 트랜잭션 안에서 즉시 commit되고 반환된 savedAuction은 이미 detached 상태다.
            // placeManualBid()로 바꾼 currentWinner/currentPrice를 실제로 반영하려면 다시
            // save()해야 한다 - 안 그러면 endIfDue()가 다시 읽는 DB row는 winner가 여전히
            // null이라 settle()이 애초에 Order INSERT를 시도하지 않는다(강제 실패도 트리거되지
            // 않음).
            auctionRepository.save(savedAuction);

            assertThatThrownBy(() -> auctionEndService.endIfDue(savedAuction.getId()))
                    .isInstanceOf(RuntimeException.class);

            Auction reloadedAuction = auctionRepository.findById(savedAuction.getId()).orElseThrow();
            assertThat(reloadedAuction.getStatus()).isEqualTo(AuctionStatus.LIVE);

            assertThat(orderRepository.count()).isZero();
        } finally {
            jdbcTemplate.execute("ALTER TABLE orders DROP CHECK chk_smoke_force_order_fail");
        }
    }

    @Test
    void 동시에_같은_Auction을_endIfDue해도_ENDED_전환과_winner_Order는_한_번만_반영된다() throws Exception {
        User seller = userRepository.save(User.register("seller-" + System.nanoTime() + "@vintic.local", "seller", null));
        User winner = userRepository.save(User.register("winner-" + System.nanoTime() + "@vintic.local", "winner", null));
        Product product = productRepository.save(new Product(
                seller,
                List.of("https://example.com/a.jpg"),
                "Nike", "Dunk Low", "Panda", 270, "B", "PARTIAL",
                300000, 350000, "285,000원 ~ 315,000원", 290000, "사유", "설명"
        ));
        Auction auction = Auction.schedule(
                product, 10000L, 5000L, LocalDateTime.now().minusHours(2), LocalDateTime.now().minusMinutes(1)
        );
        auction.start();
        Auction savedAuction = auctionRepository.save(auction);
        bidRepository.save(Bid.place(savedAuction, winner, 30000L, BidType.MANUAL));
        savedAuction.placeManualBid(winner, 30000L);
        auctionRepository.save(savedAuction); // 위 rollback 테스트와 동일한 이유로 다시 save 필요.

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Void> task = () -> {
            ready.countDown();
            start.await();
            auctionEndService.endIfDue(savedAuction.getId());
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

            Auction reloadedAuction = auctionRepository.findById(savedAuction.getId()).orElseThrow();
            assertThat(reloadedAuction.getStatus()).isEqualTo(AuctionStatus.ENDED);

            long orderCount = orderRepository.findAll().stream()
                    .filter(o -> o.getBuyer().getId().equals(winner.getId()))
                    .count();
            assertThat(orderCount)
                    .as("uk_order_auction_buyer가 동시 endIfDue()에서도 Order row 1개만 남기는지")
                    .isEqualTo(1);
        } finally {
            executor.shutdown();
        }
    }

    // #73 종료 전 추가 검증: BidCommandService.placeManualBid()와 AuctionEndService.endIfDue()를
    // 동일 Auction에 대해 실제로 동시 실행해도, 두 서비스가 공유하는 Auction FOR UPDATE 직렬화만으로
    // lifecycle invariant가 깨지지 않는지 검증한다. 새 lock이나 production delay는 추가하지 않는다.
    //
    // endAt을 이미 지난 시각(now - 10s)으로 둔다 - 어느 쪽이 먼저 lock을 잡아도 "마감 시각을 이미
    // 지난 경매"라는 사실 자체는 변하지 않으므로, 이 fixture에서 유일하게 정상인 결과는 하나뿐이다:
    // Bid가 먼저 lock을 잡아도 (Auction.hasReachedDeadline() 검증에 의해) 거절되고, End가 먼저
    // 잡으면 그대로 ENDED로 확정된다 - 결과는 lock 순서와 무관하게 항상 ENDED + Bid 거절이어야
    // 한다(if/else 분기 없음). "이미 지난 마감을 Bid가 먼저 lock을 잡아 미래로 되돌리는 것"은 이제
    // 어느 순서에서도 나와서는 안 되는 결과다 - 이 테스트는 정확히 그 회귀를 잡기 위한 것이다.
    @Test
    void 이미_마감된_Auction에서_Bid와_endIfDue가_동시_실행돼도_ENDED_전환만_확정되고_Bid는_거절된다() throws Exception {
        User seller = userRepository.save(User.register("seller-" + System.nanoTime() + "@vintic.local", "seller", null));
        User initialWinner = userRepository.save(User.register("initial-" + System.nanoTime() + "@vintic.local", "initial", null));
        User challenger = userRepository.save(User.register("challenger-" + System.nanoTime() + "@vintic.local", "challenger", null));
        Product product = productRepository.save(new Product(
                seller,
                List.of("https://example.com/a.jpg"),
                "Nike", "Dunk Low", "Panda", 270, "B", "PARTIAL",
                300000, 350000, "285,000원 ~ 315,000원", 290000, "사유", "설명"
        ));
        // endAt을 이미 지난 시각(now - 10s)으로 둔다 - endIfDue()의 "이미 마감 시각을 지났는지" 판정과
        // maybeExtend()의 EXTENSION_TRIGGER_WINDOW(마감 1분 전부터, 상한 없음) 판정을 실행 순서와
        // 무관하게 동시에 만족시키기 위함이다(#73-2 endAt은 "재조회 시점의 최신값" 기준이므로, 어느
        // 쪽이 먼저 lock을 잡아도 각자 유효한 후보로 남는다).
        LocalDateTime originalEndAt = LocalDateTime.now().minusSeconds(10);
        Auction auction = Auction.schedule(
                product, 10000L, 5000L, LocalDateTime.now().minusHours(2), originalEndAt
        );
        auction.start();
        Auction savedAuction = auctionRepository.save(auction);
        bidRepository.save(Bid.place(savedAuction, initialWinner, 15000L, BidType.MANUAL));
        savedAuction.placeManualBid(initialWinner, 15000L);
        auctionRepository.save(savedAuction); // 위 두 테스트와 동일한 이유(detached 재저장 필요).

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<String> bidTask = () -> {
            ready.countDown();
            start.await();
            try {
                bidCommandService.placeManualBid(savedAuction.getId(), challenger.getId(), 20000L);
                return "BID_SUCCESS";
            } catch (AuctionClosedException e) {
                return "BID_CLOSED";
            }
        };
        Callable<Void> endTask = () -> {
            ready.countDown();
            start.await();
            auctionEndService.endIfDue(savedAuction.getId());
            return null;
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> bidFuture = executor.submit(bidTask);
            Future<Void> endFuture = executor.submit(endTask);
            ready.await();
            start.countDown();

            String bidOutcome = bidFuture.get(30, TimeUnit.SECONDS);
            endFuture.get(30, TimeUnit.SECONDS);

            // 어느 쪽이 먼저 lock을 잡았는지와 무관하게 결과는 항상 다음과 같아야 한다: 입찰은
            // 거절되고(마감 전이라 통과했다면 AuctionClosedException을 던지지 않았을 이 챌린저
            // 입찰이 성공해서는 안 된다), Auction은 ENDED, winner/price는 원래 winner 기준 그대로,
            // settlement는 원래 winner에게 정확히 1건만 반영된다.
            assertThat(bidOutcome).isEqualTo("BID_CLOSED");

            Auction reloadedAuction = auctionRepository.findById(savedAuction.getId()).orElseThrow();
            assertThat(reloadedAuction.getStatus()).isEqualTo(AuctionStatus.ENDED);
            assertThat(reloadedAuction.getCurrentPrice()).isEqualTo(15000L);
            assertThat(reloadedAuction.getCurrentWinner().getId()).isEqualTo(initialWinner.getId());

            long challengerBidCount = bidRepository.findAll().stream()
                    .filter(b -> b.getUser().getId().equals(challenger.getId()))
                    .count();
            assertThat(challengerBidCount)
                    .as("마감 이후 챌린저의 Bid는 어느 lock 순서에서도 반영되면 안 된다")
                    .isZero();

            List<Order> orders = orderRepository.findAll();
            assertThat(orders).hasSize(1);
            assertThat(orders.get(0).getBuyer().getId()).isEqualTo(initialWinner.getId());
        } finally {
            executor.shutdown();
        }
    }
}
