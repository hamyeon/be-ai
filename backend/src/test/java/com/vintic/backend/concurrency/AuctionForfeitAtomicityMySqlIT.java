package com.vintic.backend.concurrency;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.backupoffer.repository.BackupOfferRepository;
import com.vintic.backend.bid.domain.Bid;
import com.vintic.backend.bid.domain.BidType;
import com.vintic.backend.bid.repository.BidRepository;
import com.vintic.backend.common.dto.ApiResponse;
import com.vintic.backend.order.domain.Order;
import com.vintic.backend.order.domain.OrderStatus;
import com.vintic.backend.order.repository.OrderRepository;
import com.vintic.backend.penalty.repository.PenaltyRepository;
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

// #56-2: forfeit이 정말로 Order 전이 + penalty + BackupOffer를 한 트랜잭션으로 묶는지, 그중
// 하나라도 실패하면 전부 롤백되는지를 실제 MySQL로 검증한다. AuctionPriceAuditAtomicityMySqlIT(#45)
// 가 확립한 방식(임시 CHECK 제약으로 강제 실패)을 그대로 재사용한다 - production fail hook은
// 추가하지 않는다. penalties INSERT를 강제로 실패시켜, Order/BackupOffer까지 함께 롤백되는지
// 확인한다(penalty가 BackupOffer보다 먼저 write되므로 - AuctionForfeitService 참고 - 이 지점의
// 실패가 그 뒤 모든 단계를 함께 되돌리는지가 핵심이다).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@Testcontainers
class AuctionForfeitAtomicityMySqlIT {

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
    private OrderRepository orderRepository;

    @Autowired
    private PenaltyRepository penaltyRepository;

    @Autowired
    private BackupOfferRepository backupOfferRepository;

    @Autowired
    private BidRepository bidRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private HttpEntity<Void> forfeitRequest(Long userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", String.valueOf(userId));
        return new HttpEntity<>(headers);
    }

    @Test
    void penalty_INSERT가_실패하면_Order_전이와_BackupOffer_생성도_모두_롤백된다() {
        jdbcTemplate.execute(
                "ALTER TABLE penalties ADD CONSTRAINT chk_smoke_force_penalty_fail CHECK (1 = 0)"
        );

        User winner = userRepository.save(User.register("winner-" + System.nanoTime() + "@vintic.local", "winner", null));
        User candidate = userRepository.save(User.register("candidate-" + System.nanoTime() + "@vintic.local", "candidate", null));
        User seller = userRepository.save(User.register("seller-" + System.nanoTime() + "@vintic.local", "seller", null));
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
        bidRepository.save(Bid.place(auction, candidate, 20000L, BidType.MANUAL));
        auction.placeManualBid(candidate, 20000L);
        bidRepository.save(Bid.place(auction, winner, 30000L, BidType.MANUAL));
        auction.placeManualBid(winner, 30000L);
        auction.end();
        Auction savedAuction = auctionRepository.save(auction);
        Order order = orderRepository.save(Order.createForWinner(
                savedAuction, winner, 30000L, 3000L, savedAuction.getEndAt().plusHours(24)
        ));

        String url = "/api/auctions/" + savedAuction.getId() + "/award/forfeit";
        ResponseEntity<ApiResponse<Object>> response = restTemplate.exchange(
                url, HttpMethod.POST, forfeitRequest(winner.getId()),
                new ParameterizedTypeReference<>() {
                }
        );

        // penalty INSERT가 강제로 실패하므로 이 요청은 절대 성공(200)해서는 안 된다 - 매핑되지 않은
        // DB 제약 위반이라 500으로 응답한다(이 테스트의 관심사는 상태 코드가 아니라 rollback이다).
        assertThat(response.getStatusCode().is5xxServerError()).isTrue();

        Order reloadedOrder = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(reloadedOrder.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);

        assertThat(penaltyRepository.count()).isZero();
        assertThat(backupOfferRepository.count()).isZero();
    }
}
