package com.vintic.backend.auction.audit;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.autobid.domain.AutoBidSetting;
import com.vintic.backend.autobid.domain.AutoBidSettingStatus;
import com.vintic.backend.autobid.repository.AutoBidSettingRepository;
import com.vintic.backend.bid.repository.BidRepository;
import com.vintic.backend.bid.repository.IdempotencyRepository;
import com.vintic.backend.common.dto.ApiResponse;
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
import org.springframework.http.MediaType;
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
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

// #45: Auction/Bid/AutoBidSetting 변경과 audit INSERT가 정말로 같은 트랜잭션인지, audit INSERT가
// 실패하면 전체가 롤백되는지를 실제 MySQL로 검증한다. #31 스모크 테스트가 수동으로 확인했던 방식
// (임시 CHECK 제약으로 강제 실패)을 자동화된 IT로 재사용한다 - production fail hook은 추가하지
// 않는다. 세 경로(Manual/AutoBid CREATE/AutoBid UPDATE) 모두, 강제 실패 시 Auction/Bid/
// AutoBidSetting/AuctionPriceAudit/Idempotency(claim row 자체 + 성공 snapshot) 전부가 커밋되지
// 않아야 한다. Proxy가 실제로 다른 사용자의 AutoBidSetting 상태까지 바꾸는 시나리오로 구성해서
// 그 side-effect까지 롤백되는지 강하게 검증한다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@Testcontainers
class AuctionPriceAuditAtomicityMySqlIT {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    private static final AtomicBoolean CONSTRAINT_APPLIED = new AtomicBoolean(false);

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
    private AutoBidSettingRepository autoBidSettingRepository;

    @Autowired
    private BidRepository bidRepository;

    @Autowired
    private IdempotencyRepository idempotencyRepository;

    @Autowired
    private AuctionPriceAuditRepository auctionPriceAuditRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ddl-auto:update가 auction_price_audits 테이블을 만든 "뒤"에 걸어야 하므로 @BeforeAll(컨텍스트
    // 기동 전)이 아니라 각 테스트 시작 시 보장한다 - 한 번만 걸리도록 AtomicBoolean으로 가드한다.
    private void ensureAuditInsertAlwaysFails() {
        if (CONSTRAINT_APPLIED.compareAndSet(false, true)) {
            jdbcTemplate.execute(
                    "ALTER TABLE auction_price_audits ADD CONSTRAINT chk_smoke_force_audit_fail CHECK (1 = 0)"
            );
        }
    }

    private Auction persistLiveAuction(long startPrice, long bidIncrement) {
        User seller = userRepository.save(User.register(
                "seller-" + System.identityHashCode(new Object()) + "@vintic.local", "seller", null
        ));
        Product product = productRepository.save(new Product(
                seller,
                List.of("https://example.com/a.jpg"),
                "Nike", "Dunk Low", "Panda", 270, "B", "PARTIAL",
                300000, 350000, "285,000원 ~ 315,000원", 290000, "사유", "설명"
        ));
        Auction auction = Auction.schedule(
                product, startPrice, bidIncrement, LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(1)
        );
        auction.start();
        return auctionRepository.save(auction);
    }

    private HttpHeaders headersFor(Long userId, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-User-Id", String.valueOf(userId));
        headers.set("Idempotency-Key", idempotencyKey);
        return headers;
    }

    @Test
    void Manual_Bid_처리_중_audit_INSERT가_실패하면_Auction_Bid_AutoBidSetting_Idempotency가_모두_롤백된다() {
        ensureAuditInsertAlwaysFails();

        Auction auction = persistLiveAuction(105000L, 5000L);
        User competitorUser = userRepository.save(User.register(
                "competitor-" + System.identityHashCode(new Object()) + "@vintic.local", "competitor", null
        ));
        AutoBidSetting competitor = AutoBidSetting.reserve(auction, competitorUser, 200000L);
        competitor.activate();
        autoBidSettingRepository.saveAndFlush(competitor);

        String url = "/api/auctions/" + auction.getId() + "/bids";
        ResponseEntity<ApiResponse<Object>> response = restTemplate.exchange(
                url, HttpMethod.POST,
                new HttpEntity<>(Map.of("amount", 110000L), headersFor(1L, "atomicity-manual")),
                new ParameterizedTypeReference<>() {
                }
        );

        // audit INSERT가 강제로 실패하므로 이 요청은 절대 성공(201)해서는 안 된다 - 매핑되지 않은
        // DB 제약 위반이라 500으로 응답한다(이 테스트의 관심사는 상태 코드가 아니라 rollback이다).
        assertThat(response.getStatusCode().is5xxServerError()).isTrue();

        Auction reloadedAuction = auctionRepository.findById(auction.getId()).orElseThrow();
        assertThat(reloadedAuction.getCurrentPrice()).isEqualTo(105000L);
        assertThat(reloadedAuction.getCurrentWinner()).isNull();
        assertThat(bidRepository.countByAuctionId(auction.getId())).isZero();

        // Proxy가 반영됐다면 바뀌었을 competitor의 상태도 그대로여야 한다.
        AutoBidSetting reloadedCompetitor = autoBidSettingRepository.findById(competitor.getId()).orElseThrow();
        assertThat(reloadedCompetitor.getStatus()).isEqualTo(AutoBidSettingStatus.ACTIVE);

        assertThat(auctionPriceAuditRepository.countByAuctionId(auction.getId())).isZero();
        assertThat(idempotencyRepository.findByUserIdAndOperationScopeAndIdempotencyKey(
                1L, "PLACE_BID:" + auction.getId(), "atomicity-manual"
        )).isEmpty();
    }

    @Test
    void AutoBid_CREATE_처리_중_audit_INSERT가_실패하면_모든_변경이_롤백된다() {
        ensureAuditInsertAlwaysFails();

        Auction auction = persistLiveAuction(105000L, 5000L);
        User weakerUser = userRepository.save(User.register(
                "weaker-" + System.identityHashCode(new Object()) + "@vintic.local", "weaker", null
        ));
        AutoBidSetting weaker = AutoBidSetting.reserve(auction, weakerUser, 120000L);
        weaker.activate();
        autoBidSettingRepository.saveAndFlush(weaker);

        String url = "/api/auctions/" + auction.getId() + "/auto-bids";
        ResponseEntity<ApiResponse<Object>> response = restTemplate.exchange(
                url, HttpMethod.POST,
                new HttpEntity<>(Map.of("maxAmount", 200000L), headersFor(1L, "atomicity-auto-create")),
                new ParameterizedTypeReference<>() {
                }
        );

        assertThat(response.getStatusCode().is5xxServerError()).isTrue();

        Auction reloadedAuction = auctionRepository.findById(auction.getId()).orElseThrow();
        assertThat(reloadedAuction.getCurrentPrice()).isEqualTo(105000L);

        // entrant(1L)의 AutoBidSetting은 저장 자체가 롤백되어 존재하지 않아야 한다.
        assertThat(autoBidSettingRepository.findByAuctionIdAndUserIdAndActiveSlotTrue(auction.getId(), 1L)).isEmpty();
        // 기존 경쟁자도 CAP_REACHED로 바뀌지 않고 그대로여야 한다.
        AutoBidSetting reloadedWeaker = autoBidSettingRepository.findById(weaker.getId()).orElseThrow();
        assertThat(reloadedWeaker.getStatus()).isEqualTo(AutoBidSettingStatus.ACTIVE);
        assertThat(bidRepository.countByAuctionId(auction.getId())).isZero();

        assertThat(auctionPriceAuditRepository.countByAuctionId(auction.getId())).isZero();
        assertThat(idempotencyRepository.findByUserIdAndOperationScopeAndIdempotencyKey(
                1L, "CREATE_AUTO_BID:" + auction.getId(), "atomicity-auto-create"
        )).isEmpty();
    }

    @Test
    void AutoBid_UPDATE_처리_중_audit_INSERT가_실패하면_모든_변경이_롤백된다() {
        ensureAuditInsertAlwaysFails();

        Auction auction = persistLiveAuction(105000L, 5000L);
        User weakerUser = userRepository.save(User.register(
                "weaker2-" + System.identityHashCode(new Object()) + "@vintic.local", "weaker2", null
        ));
        AutoBidSetting weaker = AutoBidSetting.reserve(auction, weakerUser, 120000L);
        weaker.activate();
        autoBidSettingRepository.saveAndFlush(weaker);

        AutoBidSetting mine = AutoBidSetting.reserve(auction, userRepository.findById(1L).orElseThrow(), 110000L);
        mine.activate();
        mine.markCapReached();
        autoBidSettingRepository.saveAndFlush(mine);

        String url = "/api/auctions/" + auction.getId() + "/auto-bids/me";
        ResponseEntity<ApiResponse<Object>> response = restTemplate.exchange(
                url, HttpMethod.PATCH,
                new HttpEntity<>(Map.of("maxAmount", 200000L), headersFor(1L, "atomicity-auto-update")),
                new ParameterizedTypeReference<>() {
                }
        );

        assertThat(response.getStatusCode().is5xxServerError()).isTrue();

        Auction reloadedAuction = auctionRepository.findById(auction.getId()).orElseThrow();
        assertThat(reloadedAuction.getCurrentPrice()).isEqualTo(105000L);

        // 내 설정의 maxAmount/status가 이전 값(CAP_REACHED, 110000) 그대로여야 한다 - 상향이
        // 반영되면 안 된다.
        AutoBidSetting reloadedMine = autoBidSettingRepository.findById(mine.getId()).orElseThrow();
        assertThat(reloadedMine.getMaxAmount()).isEqualTo(110000L);
        assertThat(reloadedMine.getStatus()).isEqualTo(AutoBidSettingStatus.CAP_REACHED);
        AutoBidSetting reloadedWeaker = autoBidSettingRepository.findById(weaker.getId()).orElseThrow();
        assertThat(reloadedWeaker.getStatus()).isEqualTo(AutoBidSettingStatus.ACTIVE);
        assertThat(bidRepository.countByAuctionId(auction.getId())).isZero();

        assertThat(auctionPriceAuditRepository.countByAuctionId(auction.getId())).isZero();
        assertThat(idempotencyRepository.findByUserIdAndOperationScopeAndIdempotencyKey(
                1L, "UPDATE_AUTO_BID:" + auction.getId(), "atomicity-auto-update"
        )).isEmpty();
    }

}
