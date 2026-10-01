package com.vintic.backend.concurrency;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.backupoffer.domain.BackupOffer;
import com.vintic.backend.backupoffer.domain.BackupOfferStatus;
import com.vintic.backend.backupoffer.repository.BackupOfferRepository;
import com.vintic.backend.backupoffer.service.BackupOfferExpirationService;
import com.vintic.backend.bid.domain.Bid;
import com.vintic.backend.bid.domain.BidType;
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
import org.springframework.test.util.ReflectionTestUtils;
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

// #57-2: BackupOfferExpirationService.expireIfDue()의 (a) 동시 실행 correctness - Auction/
// BackupOffer FOR UPDATE가 두 호출을 직렬화해 다음 순위(rank3) 제안이 정확히 1건만 생기는지,
// (b) 트랜잭션 원자성 - 다음 BackupOffer INSERT가 실패하면 원래 offer의 EXPIRED 전이까지
// 롤백되는지를 실제 MySQL로 검증한다. OrderExpirationConcurrencyMySqlIT와 동일한 harness.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Testcontainers
class BackupOfferExpirationConcurrencyMySqlIT {

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
    private BackupOfferExpirationService backupOfferExpirationService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private AuctionRepository auctionRepository;

    @Autowired
    private BidRepository bidRepository;

    @Autowired
    private BackupOfferRepository backupOfferRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private record Fixture(Auction auction, User rank2, User rank3, BackupOffer offer) {
    }

    private Fixture persistPastDueRank2Offer() {
        User seller = userRepository.save(User.register("seller-" + System.nanoTime() + "@vintic.local", "seller", null));
        User winner = userRepository.save(User.register("winner-" + System.nanoTime() + "@vintic.local", "winner", null));
        User rank2 = userRepository.save(User.register("rank2-" + System.nanoTime() + "@vintic.local", "rank2", null));
        User rank3 = userRepository.save(User.register("rank3-" + System.nanoTime() + "@vintic.local", "rank3", null));
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
        bidRepository.save(Bid.place(savedAuction, rank3, 15000L, BidType.MANUAL));
        savedAuction.placeManualBid(rank3, 15000L);
        bidRepository.save(Bid.place(savedAuction, rank2, 20000L, BidType.MANUAL));
        savedAuction.placeManualBid(rank2, 20000L);
        bidRepository.save(Bid.place(savedAuction, winner, 25000L, BidType.MANUAL));
        savedAuction.placeManualBid(winner, 25000L);
        savedAuction.end();
        BackupOffer offer = backupOfferRepository.save(BackupOffer.create(savedAuction, rank2, 20000L));
        ReflectionTestUtils.setField(offer, "deadline", LocalDateTime.now().minusMinutes(1));
        backupOfferRepository.save(offer);
        return new Fixture(savedAuction, rank2, rank3, offer);
    }

    @Test
    void 동시에_같은_제안을_만료처리해도_다음_제안은_1건만_생성된다() throws Exception {
        Fixture fixture = persistPastDueRank2Offer();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Void> task = () -> {
            ready.countDown();
            start.await();
            backupOfferExpirationService.expireIfDue(fixture.offer().getId());
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

            BackupOffer reloaded = backupOfferRepository.findById(fixture.offer().getId()).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(BackupOfferStatus.EXPIRED);
            assertThat(backupOfferRepository.count()).isEqualTo(2); // rank2(EXPIRED) + rank3(WAITING)

            BackupOffer nextOffer = backupOfferRepository
                    .findByAuctionIdAndCandidateId(fixture.auction().getId(), fixture.rank3().getId())
                    .orElseThrow();
            assertThat(nextOffer.getStatus()).isEqualTo(BackupOfferStatus.WAITING);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void 다음_BackupOffer_INSERT가_실패하면_원래_제안의_EXPIRED_전이도_롤백된다() {
        // fixture(rank2 offer, purchase_price=20000 1건)를 먼저 만든 뒤에 제약을 건다 - 제약을
        // 먼저 걸면 MySQL이 ADD CONSTRAINT 시점에 기존 row까지 검증하므로(1=0 같은 전체 차단
        // 조건은 기존 row도 위반해 ALTER 자체가 실패한다) fixture 생성 자체가 막힌다. 대신
        // "다음 순위(rank3, purchase_price=15000) offer INSERT만" 위반하는 조건을 쓴다 - 기존
        // rank2 row(20000)는 이 조건을 만족해 ALTER는 성공하고, expireIfDue()가 시도하는
        // rank3 offer INSERT만 여기 걸려 실패한다.
        Fixture fixture = persistPastDueRank2Offer();
        // 같은 클래스의 다른 테스트가 먼저 실행돼 purchase_price=15000인 row(rank3 offer)를
        // 남겼으면 ADD CONSTRAINT 시점에 기존 row 검증에서 곧바로 실패한다 - 이 테스트가 방금
        // 만든 rank2 row(20000)만 남기고 나머지는 지워 순서 의존성을 없앤다.
        jdbcTemplate.execute("DELETE FROM backup_offers WHERE id <> " + fixture.offer().getId());
        jdbcTemplate.execute("ALTER TABLE backup_offers ADD CONSTRAINT chk_smoke_force_offer_fail CHECK (purchase_price <> 15000)");
        try {
            assertThatThrownBy(() -> backupOfferExpirationService.expireIfDue(fixture.offer().getId()))
                    .isInstanceOf(RuntimeException.class);

            BackupOffer reloaded = backupOfferRepository.findById(fixture.offer().getId()).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(BackupOfferStatus.WAITING);
            assertThat(backupOfferRepository.count()).isEqualTo(1);
        } finally {
            jdbcTemplate.execute("ALTER TABLE backup_offers DROP CHECK chk_smoke_force_offer_fail");
        }
    }
}
