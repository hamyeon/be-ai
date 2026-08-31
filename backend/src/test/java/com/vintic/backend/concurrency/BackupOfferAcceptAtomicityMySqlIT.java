package com.vintic.backend.concurrency;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.backupoffer.domain.BackupOffer;
import com.vintic.backend.backupoffer.domain.BackupOfferStatus;
import com.vintic.backend.backupoffer.repository.BackupOfferRepository;
import com.vintic.backend.bid.domain.Bid;
import com.vintic.backend.bid.domain.BidType;
import com.vintic.backend.bid.repository.BidRepository;
import com.vintic.backend.bid.repository.IdempotencyRepository;
import com.vintic.backend.common.dto.ApiResponse;
import com.vintic.backend.order.repository.OrderRepository;
import com.vintic.backend.product.domain.Product;
import com.vintic.backend.product.repository.ProductRepository;
import com.vintic.backend.user.domain.User;
import com.vintic.backend.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// #56-3: accept 트랜잭션(offer.accept() + Order 생성 + Idempotency claim)이 정말로 하나의
// 트랜잭션인지, Order INSERT가 실패하면 offer 상태 전이와 Idempotency claim까지 함께
// 롤백되는지를 실제 MySQL로 검증한다. #45/#56-2가 확립한 방식(임시 CHECK 제약으로 강제 실패)을
// 그대로 재사용한다 - production fail hook은 추가하지 않는다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@Testcontainers
class BackupOfferAcceptAtomicityMySqlIT {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired
    private TestRestTemplate restTemplate;

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
    private OrderRepository orderRepository;

    @Autowired
    private IdempotencyRepository idempotencyRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void Order_INSERT가_실패하면_offer_상태_전이와_Idempotency_claim도_모두_롤백된다() {
        jdbcTemplate.execute(
                "ALTER TABLE orders ADD CONSTRAINT chk_smoke_force_order_fail CHECK (1 = 0)"
        );

        User seller = userRepository.save(User.register("seller-" + System.nanoTime() + "@vintic.local", "seller", null));
        User winner = userRepository.save(User.register("winner-" + System.nanoTime() + "@vintic.local", "winner", null));
        User candidate = userRepository.save(User.register("candidate-" + System.nanoTime() + "@vintic.local", "candidate", null));
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
        bidRepository.save(Bid.place(savedAuction, candidate, 15000L, BidType.MANUAL));
        savedAuction.placeManualBid(candidate, 15000L);
        bidRepository.save(Bid.place(savedAuction, winner, 20000L, BidType.MANUAL));
        savedAuction.placeManualBid(winner, 20000L);
        savedAuction.end();
        BackupOffer offer = backupOfferRepository.save(BackupOffer.create(savedAuction, candidate, 15000L));

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", String.valueOf(candidate.getId()));
        headers.set("Idempotency-Key", "atomicity-accept");
        String url = "/api/backup-offers/" + offer.getId() + "/accept";

        ResponseEntity<ApiResponse<Object>> response = restTemplate.exchange(
                url, HttpMethod.POST, new HttpEntity<>(headers),
                new ParameterizedTypeReference<>() {
                }
        );

        // orders INSERT가 강제로 실패하므로 이 요청은 절대 성공(201)해서는 안 된다.
        assertThat(response.getStatusCode().is5xxServerError()).isTrue();

        BackupOffer reloadedOffer = backupOfferRepository.findById(offer.getId()).orElseThrow();
        assertThat(reloadedOffer.getStatus()).isEqualTo(BackupOfferStatus.WAITING);

        assertThat(orderRepository.count()).isZero();
        assertThat(idempotencyRepository.findByUserIdAndOperationScopeAndIdempotencyKey(
                candidate.getId(), "ACCEPT_BACKUP_OFFER:" + offer.getId(), "atomicity-accept"
        )).isEmpty();
    }
}
