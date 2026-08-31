package com.vintic.backend.concurrency;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.order.domain.Order;
import com.vintic.backend.order.repository.OrderRepository;
import com.vintic.backend.order.service.AuctionSettlementService;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

// #56-1: AuctionSettlementService.settle()은 아직 HTTP endpoint가 없다(스케줄러 통합 전까지는
// 테스트/향후 lifecycle 호출부가 직접 호출한다) - 그래서 이 IT는 TestRestTemplate이 아니라
// Spring이 관리하는 서비스 빈을 여러 스레드에서 직접 호출한다. AuctionLikeConcurrencyMySqlIT/
// AutoBidConcurrencyMySqlIT와 같은 harness(CountDownLatch + ExecutorService)를 재사용한다.
//
// 검증 대상: 같은 auction에 대한 동시 settle() 호출이 Auction row lock(findByIdForUpdate)으로
// 직렬화되어 uk_order_auction_buyer UNIQUE에 의존하지 않고도 Order가 정확히 1건만 남는지 -
// #56-0이 요구한 "service check만으로 중복을 보장하지 않는다" DB invariant가 실제 MySQL에서도
// 지켜지는지 확인한다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Testcontainers
class AuctionSettlementMySqlIT {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired
    private AuctionSettlementService auctionSettlementService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private AuctionRepository auctionRepository;

    @Autowired
    private OrderRepository orderRepository;

    private Auction persistEndedAuctionWithWinner() {
        User seller = userRepository.save(User.register(
                "seller-" + System.nanoTime() + "@vintic.local", "seller", null
        ));
        User winner = userRepository.save(User.register(
                "winner-" + System.nanoTime() + "@vintic.local", "winner", null
        ));
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
        auction.placeManualBid(winner, 30000L);
        auction.end();
        return auctionRepository.save(auction);
    }

    @Test
    void 같은_경매를_동시에_settle해도_Order는_1건만_생성된다() throws Exception {
        Auction auction = persistEndedAuctionWithWinner();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Optional<Order>> task = () -> {
            ready.countDown();
            start.await();
            return auctionSettlementService.settle(auction.getId());
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Optional<Order>> future1 = executor.submit(task);
            Future<Optional<Order>> future2 = executor.submit(task);
            ready.await();
            start.countDown();

            Optional<Order> result1 = future1.get(30, TimeUnit.SECONDS);
            Optional<Order> result2 = future2.get(30, TimeUnit.SECONDS);

            assertThat(result1).isPresent();
            assertThat(result2).isPresent();
            // Auction row lock이 두 호출을 완전히 직렬화하므로 나중에 실행된 쪽도 새로
            // 생성하지 않고 먼저 만들어진 Order를 그대로 본다.
            assertThat(result1.get().getId()).isEqualTo(result2.get().getId());

            long orderCount = orderRepository.findAll().stream()
                    .filter(o -> o.getAuction().getId().equals(auction.getId()))
                    .count();
            assertThat(orderCount)
                    .as("uk_order_auction_buyer가 동시 settle에서도 row 1개만 남기는지")
                    .isEqualTo(1);
        } finally {
            executor.shutdown();
        }
    }
}
