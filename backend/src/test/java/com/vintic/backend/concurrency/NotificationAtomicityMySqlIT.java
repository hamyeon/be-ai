package com.vintic.backend.concurrency;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.backupoffer.repository.BackupOfferRepository;
import com.vintic.backend.bid.domain.Bid;
import com.vintic.backend.bid.domain.BidType;
import com.vintic.backend.bid.repository.BidRepository;
import com.vintic.backend.common.dto.ApiResponse;
import com.vintic.backend.notification.repository.NotificationRepository;
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

// #75: NotificationRecorder는 새 트랜잭션을 열지 않고 호출자(lifecycle 서비스)의 트랜잭션에
// 그대로 참여해야 한다는 invariant를 실제 MySQL로 검증한다. AuctionForfeitAtomicityMySqlIT(#56-2)/
// AuctionPriceAuditAtomicityMySqlIT(#45)가 확립한 방식(임시 CHECK 제약으로 강제 실패)을 notifications
// 테이블에 재사용한다 - production fail hook은 추가하지 않는다. AuctionForfeitAtomicityMySqlIT와
// 같은 클래스에 두 번째 @Test로 추가하지 않은 이유: 같은 컨테이너/스키마를 공유하는 두 테스트가
// 서로 다른 테이블에 영구 CHECK 제약을 남기면, 나중에 실행되는 테스트가 앞 테스트의 잔여 제약
// 때문에 의도와 다른 이유로도 항상 500이 나와 검증이 무의미해질 수 있다 - 독립된 컨테이너를
// 쓰는 별도 클래스로 분리해 이 문제를 피한다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@Testcontainers
class NotificationAtomicityMySqlIT {

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
    private NotificationRepository notificationRepository;

    @Autowired
    private BidRepository bidRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private HttpEntity<Void> forfeitRequest(Long userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", String.valueOf(userId));
        return new HttpEntity<>(headers);
    }

    // AuctionForfeitService.forfeit()이 호출하는 BACKUP_OFFER_CREATED NotificationRecorder.record()
    // 지점을 강제로 실패시켜, 같은 트랜잭션의 Order 전이/penalty/BackupOffer까지 모두 롤백되는지
    // 확인한다 - AuctionForfeitAtomicityMySqlIT가 이미 검증한 "penalty 실패 -> 전체 롤백"의
    // 역방향(Notification 실패 -> 전체 롤백)이다.
    @Test
    void notification_INSERT가_실패하면_Order_전이와_penalty_BackupOffer_생성도_모두_롤백된다() {
        jdbcTemplate.execute(
                "ALTER TABLE notifications ADD CONSTRAINT chk_smoke_force_notification_fail CHECK (1 = 0)"
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
        Auction savedAuction = auctionRepository.save(auction);
        bidRepository.save(Bid.place(savedAuction, candidate, 20000L, BidType.MANUAL));
        savedAuction.placeManualBid(candidate, 20000L);
        bidRepository.save(Bid.place(savedAuction, winner, 30000L, BidType.MANUAL));
        savedAuction.placeManualBid(winner, 30000L);
        savedAuction.end();
        Order order = orderRepository.save(Order.createForWinner(
                savedAuction, winner, 30000L, 3000L, savedAuction.getEndAt().plusHours(24)
        ));

        String url = "/api/auctions/" + savedAuction.getId() + "/award/forfeit";
        ResponseEntity<ApiResponse<Object>> response = restTemplate.exchange(
                url, HttpMethod.POST, forfeitRequest(winner.getId()),
                new ParameterizedTypeReference<>() {
                }
        );

        // notifications INSERT가 강제로 실패하므로 이 요청은 절대 성공(200)해서는 안 된다 - 매핑되지
        // 않은 DB 제약 위반이라 500으로 응답한다(이 테스트의 관심사는 상태 코드가 아니라 rollback이다).
        assertThat(response.getStatusCode().is5xxServerError()).isTrue();

        Order reloadedOrder = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(reloadedOrder.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);

        assertThat(penaltyRepository.count()).isZero();
        assertThat(backupOfferRepository.count()).isZero();
        assertThat(notificationRepository.count()).isZero();
    }
}
