package com.vintic.backend.concurrency;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.domain.AuctionStatus;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.auction.service.AuctionStartService;
import com.vintic.backend.autobid.domain.AutoBidSetting;
import com.vintic.backend.autobid.domain.AutoBidSettingStatus;
import com.vintic.backend.autobid.repository.AutoBidSettingRepository;
import com.vintic.backend.bid.repository.BidRepository;
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

// #73-1/#73-4: AuctionStartService.startIfDue()가 Auction LIVE 전환 + RESERVED AutoBid 정산을
// 하나의 트랜잭션으로 묶는지, 그리고 동일 Auction에 대한 실제 동시 invocation에도 한 번만
// 반영되는지 실제 MySQL로 검증한다. Bid INSERT를 강제로 실패시키는 rollback 케이스(#45/#56-2/
// #57-2가 확립한 임시 CHECK 제약 방식)와, CountDownLatch 2-thread 동시 실행 케이스(#57-2
// OrderExpirationConcurrencyMySqlIT와 동일한 harness) 둘 다 이 클래스 하나에 둔다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Testcontainers
class AuctionStartAtomicityMySqlIT {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired
    private AuctionStartService auctionStartService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private AuctionRepository auctionRepository;

    @Autowired
    private AutoBidSettingRepository autoBidSettingRepository;

    @Autowired
    private BidRepository bidRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void Bid_INSERT가_실패하면_Auction_LIVE_전환과_AutoBid_ACTIVE_전환도_모두_롤백된다() {
        jdbcTemplate.execute("ALTER TABLE bids ADD CONSTRAINT chk_smoke_force_bid_fail CHECK (1 = 0)");
        try {
            User seller = userRepository.save(User.register("seller-" + System.nanoTime() + "@vintic.local", "seller", null));
            User bidder = userRepository.save(User.register("bidder-" + System.nanoTime() + "@vintic.local", "bidder", null));
            Product product = productRepository.save(new Product(
                    seller,
                    List.of("https://example.com/a.jpg"),
                    "Nike", "Dunk Low", "Panda", 270, "B", "PARTIAL",
                    300000, 350000, "285,000원 ~ 315,000원", 290000, "사유", "설명"
            ));
            Auction auction = Auction.schedule(
                    product, 10000L, 5000L, LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1)
            );
            Auction savedAuction = auctionRepository.save(auction);
            AutoBidSetting setting = autoBidSettingRepository.save(AutoBidSetting.reserve(savedAuction, bidder, 30000L));

            assertThatThrownBy(() -> auctionStartService.startIfDue(savedAuction.getId()))
                    .isInstanceOf(RuntimeException.class);

            Auction reloadedAuction = auctionRepository.findById(savedAuction.getId()).orElseThrow();
            assertThat(reloadedAuction.getStatus()).isEqualTo(AuctionStatus.SCHEDULED);
            assertThat(reloadedAuction.getCurrentWinner()).isNull();

            AutoBidSetting reloadedSetting = autoBidSettingRepository.findById(setting.getId()).orElseThrow();
            assertThat(reloadedSetting.getStatus()).isEqualTo(AutoBidSettingStatus.RESERVED);

            assertThat(bidRepository.count()).isZero();
        } finally {
            jdbcTemplate.execute("ALTER TABLE bids DROP CHECK chk_smoke_force_bid_fail");
        }
    }

    @Test
    void 동시에_같은_Auction을_startIfDue해도_LIVE_전환과_AutoBid_정산은_한_번만_반영된다() throws Exception {
        User seller = userRepository.save(User.register("seller-" + System.nanoTime() + "@vintic.local", "seller", null));
        User strong = userRepository.save(User.register("strong-" + System.nanoTime() + "@vintic.local", "strong", null));
        User weak = userRepository.save(User.register("weak-" + System.nanoTime() + "@vintic.local", "weak", null));
        Product product = productRepository.save(new Product(
                seller,
                List.of("https://example.com/a.jpg"),
                "Nike", "Dunk Low", "Panda", 270, "B", "PARTIAL",
                300000, 350000, "285,000원 ~ 315,000원", 290000, "사유", "설명"
        ));
        Auction auction = Auction.schedule(
                product, 10000L, 5000L, LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1)
        );
        Auction savedAuction = auctionRepository.save(auction);
        autoBidSettingRepository.save(AutoBidSetting.reserve(savedAuction, strong, 50000L));
        autoBidSettingRepository.save(AutoBidSetting.reserve(savedAuction, weak, 15000L));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Void> task = () -> {
            ready.countDown();
            start.await();
            auctionStartService.startIfDue(savedAuction.getId());
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
            assertThat(reloadedAuction.getStatus()).isEqualTo(AuctionStatus.LIVE);
            // top=strong(50000), second=weak(15000) -> finalPrice = min(50000, 15000+5000) = 20000.
            assertThat(reloadedAuction.getCurrentPrice()).isEqualTo(20000L);
            assertThat(reloadedAuction.getCurrentWinner().getId()).isEqualTo(strong.getId());

            // 동시 실행이어도 정산은 정확히 한 번만 반영돼야 한다 - Bid 중복 생성 없음.
            assertThat(bidRepository.count()).isEqualTo(1);

            AutoBidSetting strongSetting = autoBidSettingRepository
                    .findByAuctionIdAndUserIdAndActiveSlotTrue(savedAuction.getId(), strong.getId()).orElseThrow();
            AutoBidSetting weakSetting = autoBidSettingRepository
                    .findByAuctionIdAndUserIdAndActiveSlotTrue(savedAuction.getId(), weak.getId()).orElseThrow();
            assertThat(strongSetting.getStatus()).isEqualTo(AutoBidSettingStatus.ACTIVE);
            assertThat(weakSetting.getStatus()).isEqualTo(AutoBidSettingStatus.CAP_REACHED);
        } finally {
            executor.shutdown();
        }
    }
}
