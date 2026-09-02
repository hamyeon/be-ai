package com.vintic.backend.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.ai.search.embedding.EmbeddingClient;
import com.vintic.backend.analyze.queue.AnalysisTaskProducer;
import com.vintic.backend.analyze.service.S3UploaderService;
import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.domain.AuctionStatus;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.auction.service.AuctionEndService;
import com.vintic.backend.backupoffer.domain.BackupOffer;
import com.vintic.backend.backupoffer.repository.BackupOfferRepository;
import com.vintic.backend.order.domain.Order;
import com.vintic.backend.order.domain.OrderStatus;
import com.vintic.backend.order.repository.OrderRepository;
import com.vintic.backend.order.service.AuctionSettlementService;
import com.vintic.backend.order.service.OrderExpirationService;
import com.vintic.backend.product.domain.Product;
import com.vintic.backend.product.repository.ProductRepository;
import com.vintic.backend.recommendation.repository.ProductVectorRepository;
import com.vintic.backend.recommendation.repository.UserActivityLogRepository;
import com.vintic.backend.user.domain.User;
import com.vintic.backend.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 상품 등록부터 결제까지 전 구간을 한 흐름으로 이어본다.
//
// 두 사람이 각자 만든 구간이 처음으로 한 테스트에서 만난다. 조각별 테스트가 전부
// 초록불인데 이어붙이면 깨지는 경우를 이미 두 번 겪었고(docs/troubleshooting.md 1·2번),
// 지금은 그 사이에 스케줄러와 트랜잭션 경계가 여러 번 끼어 있어 위험이 더 크다.
//
// AiTrackE2EMySqlIT와 나눈 이유:
//   그쪽은 AI·조회 트랙 안쪽(벡터 생성, 추천 정렬, 캐시 무효화, 분석 상태 전이)을 본다.
//   이쪽은 트랙 사이의 이음매(경매 -> 낙찰 -> 주문 -> 결제)를 본다. 한 파일에 합치면
//   실패했을 때 어느 관심사가 깨졌는지 흐려진다.
//
// 스케줄러를 직접 호출하는 이유:
//   경매 종료·결제 만료가 전부 @Scheduled이고 기본값이 꺼져 있다(application.yml).
//   cron 주기를 기다리면 테스트가 분 단위로 느려지고 타이밍에 좌우된다. 여기서 볼 것은
//   "cron 표현식이 맞는가"가 아니라 "돌고 나면 상태가 올바른가"다.
//
// 경매 생성을 리포지토리로 하는 이유:
//   POST /api/auctions가 없다. 상품 등록 시 자동 생성으로 가는 방향이지만 아직
//   구현되지 않았다. 그 경로가 생기면 openAuction() 하나만 바꾸면 되도록 격리했다.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Testcontainers
class FullFlowE2EMySqlIT {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private AuctionRepository auctionRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private BackupOfferRepository backupOfferRepository;

    @Autowired
    private ProductVectorRepository productVectorRepository;

    @Autowired
    private UserActivityLogRepository activityLogRepository;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    // 스케줄러가 부르는 서비스를 직접 호출한다
    @Autowired
    private AuctionEndService auctionEndService;

    @Autowired
    private AuctionSettlementService auctionSettlementService;

    @Autowired
    private OrderExpirationService orderExpirationService;

    // 외부 경계는 목으로 둔다
    @MockitoBean
    private EmbeddingClient embeddingClient;

    @MockitoBean
    private S3UploaderService s3UploaderService;

    @MockitoBean
    private AnalysisTaskProducer analysisTaskProducer;

    private Long sellerId;
    private Long buyerId;
    private Long secondBuyerId;

    @BeforeEach
    void setUp() {
        clearAllTables();

        sellerId = userRepository.save(User.register("seller@vintic.local", "seller", null)).getId();
        buyerId = userRepository.save(User.register("buyer@vintic.local", "buyer", null)).getId();
        secondBuyerId = userRepository.save(User.register("buyer2@vintic.local", "buyer2", null)).getId();

        float[] vector = new float[1536];
        vector[0] = 1.0f;
        when(embeddingClient.embed(anyString())).thenReturn(vector);
    }

    // ------------------------------------------------------------------
    // 헬퍼
    // ------------------------------------------------------------------

    /**
     * 모든 테이블을 비운다.
     *
     * <p>경매 하나를 지우려면 auction_likes, bids, auto_bid_settings, backup_offers,
     * orders, penalties가 먼저 사라져야 한다. 리포지토리를 순서대로 나열하면 테이블이
     * 하나 늘 때마다 이 테스트가 깨지므로, FK 검사를 잠시 끄고 전부 지운다.
     * 테스트 전용 컨테이너라 다른 데이터에 영향이 없다.
     */
    private void clearAllTables() {
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 0");
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()",
                String.class);
        tables.forEach(table -> jdbcTemplate.execute("TRUNCATE TABLE `" + table + "`"));
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 1");
    }

    private Long registerProduct() throws Exception {
        String body = mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", sellerId)
                        .content("""
                                {
                                  "imageUrls": ["https://example.com/a.jpg","https://example.com/b.jpg","https://example.com/c.jpg"],
                                  "brand": "Nike", "modelName": "Dunk Low", "color": "Panda", "size": 270,
                                  "conditionGrade": "A", "componentStatus": "FULL",
                                  "recommendedPrice": 180000, "baseMarketPrice": 185000,
                                  "priceRange": "170,000원 ~ 190,000원", "sellingPrice": 180000,
                                  "reason": "E2E", "sellerDescription": "E2E"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("id").asLong();
    }

    /**
     * 진행 중인 경매를 만든다.
     *
     * <p>POST /api/auctions가 없어 리포지토리로 만든다. 상품 등록 시 자동 생성이
     * 구현되면 이 메서드만 바꾸면 나머지 검증은 그대로 쓸 수 있다.
     */
    private Long openAuction(Long productId, LocalDateTime endAt) {
        Product product = productRepository.findById(productId).orElseThrow();
        Auction auction = Auction.schedule(product, 100_000L, 5_000L,
                LocalDateTime.now().minusHours(1), endAt);
        ReflectionTestUtils.setField(auction, "status", AuctionStatus.LIVE);
        return auctionRepository.save(auction).getId();
    }

    private void placeBid(Long auctionId, Long userId, long amount, String key) throws Exception {
        mockMvc.perform(post("/api/auctions/{id}/bids", auctionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", userId)
                        .header("Idempotency-Key", key)
                        .content("{\"amount\": %d}".formatted(amount)))
                .andExpect(status().isCreated());
    }

    /** 경매를 마감시키고 낙찰 처리까지 진행한다. 스케줄러가 하는 일과 같다. */
    private Order endAuctionAndSettle(Long auctionId) {
        Auction auction = auctionRepository.findById(auctionId).orElseThrow();
        // 마감 시각을 과거로 당겨 "마감 대상"으로 만든다
        ReflectionTestUtils.setField(auction, "endAt", LocalDateTime.now().minusMinutes(1));
        auctionRepository.saveAndFlush(auction);

        auctionEndService.endIfDue(auctionId);
        return auctionSettlementService.settle(auctionId).orElse(null);
    }

    // ------------------------------------------------------------------
    // 1. 해피패스 — 상품 등록부터 결제까지
    // ------------------------------------------------------------------

    @Test
    @DisplayName("상품 등록 → 경매 → 입찰 → 낙찰 → 결제까지 끊기지 않고 이어진다")
    void 전체_플로우가_끝까지_이어진다() throws Exception {
        // 1) 상품 등록 - 추천용 벡터가 함께 만들어진다
        Long productId = registerProduct();
        assertThat(productVectorRepository.findById(productId)).isPresent();

        // 2) 경매 시작
        Long auctionId = openAuction(productId, LocalDateTime.now().plusHours(2));

        // 3) 조회 - 행동 로그가 쌓인다
        mockMvc.perform(get("/api/auctions/{id}", auctionId).header("X-User-Id", buyerId))
                .andExpect(status().isOk());
        assertThat(activityLogRepository.countByUserId(buyerId)).isEqualTo(1);

        // 4) 두 사람이 입찰 - 뒤에 넣은 쪽이 최고가가 된다
        placeBid(auctionId, buyerId, 105_000L, "e2e-bid-1");
        placeBid(auctionId, secondBuyerId, 110_000L, "e2e-bid-2");

        assertThat(auctionRepository.findById(auctionId).orElseThrow().getCurrentPrice())
                .isEqualTo(110_000L);

        // 5) 마감 → 낙찰 → 주문 생성
        Order order = endAuctionAndSettle(auctionId);

        assertThat(order).isNotNull();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(auctionRepository.findById(auctionId).orElseThrow().getStatus())
                .isEqualTo(AuctionStatus.ENDED);

        // 6) 낙찰 결과 조회 - 낙찰자가 확인할 수 있어야 한다
        mockMvc.perform(get("/api/auctions/{id}/result", auctionId)
                        .header("X-User-Id", secondBuyerId))
                .andExpect(status().isOk());

        // 7) 결제 승인
        mockMvc.perform(post("/api/orders/{orderId}/pay", order.getId())
                        .header("X-User-Id", secondBuyerId))
                .andExpect(status().isOk());

        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PAID);
    }

    @Test
    @DisplayName("입찰이 없으면 낙찰자도 주문도 생기지 않는다")
    void 입찰이_없는_경매는_주문을_만들지_않는다() throws Exception {
        Long auctionId = openAuction(registerProduct(), LocalDateTime.now().plusHours(2));

        Order order = endAuctionAndSettle(auctionId);

        assertThat(order).isNull();
        assertThat(orderRepository.findAll()).isEmpty();
    }

    // ------------------------------------------------------------------
    // 2. 실패 케이스 — 미결제와 차순위 이양
    // ------------------------------------------------------------------

    @Test
    @DisplayName("미결제 타임아웃이 차순위 제안으로 이어진다")
    void 미결제가_차순위_이양으로_이어진다() throws Exception {
        Long auctionId = openAuction(registerProduct(), LocalDateTime.now().plusHours(2));
        placeBid(auctionId, buyerId, 105_000L, "e2e-backup-1");
        placeBid(auctionId, secondBuyerId, 110_000L, "e2e-backup-2");

        Order order = endAuctionAndSettle(auctionId);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);

        // 결제 기한을 지나게 만든 뒤 만료 처리 - 스케줄러가 하는 일과 같다
        ReflectionTestUtils.setField(order, "paymentDeadline", LocalDateTime.now().minusMinutes(1));
        orderRepository.saveAndFlush(order);
        orderExpirationService.expireIfDue(order.getId());

        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PAYMENT_EXPIRED);

        // 차순위 입찰자에게 제안이 간다
        List<BackupOffer> offers = backupOfferRepository.findAll();
        assertThat(offers).hasSize(1);
        assertThat(offers.get(0).getCandidate().getId()).isEqualTo(buyerId);
    }

    @Test
    @DisplayName("차순위 수락이 새 주문으로 이어진다")
    void 차순위_수락이_주문을_만든다() throws Exception {
        Long auctionId = openAuction(registerProduct(), LocalDateTime.now().plusHours(2));
        placeBid(auctionId, buyerId, 105_000L, "e2e-accept-1");
        placeBid(auctionId, secondBuyerId, 110_000L, "e2e-accept-2");

        Order first = endAuctionAndSettle(auctionId);
        ReflectionTestUtils.setField(first, "paymentDeadline", LocalDateTime.now().minusMinutes(1));
        orderRepository.saveAndFlush(first);
        orderExpirationService.expireIfDue(first.getId());

        BackupOffer offer = backupOfferRepository.findAll().get(0);

        mockMvc.perform(post("/api/backup-offers/{id}/accept", offer.getId())
                        .header("X-User-Id", buyerId)
                        .header("Idempotency-Key", "e2e-backup-accept"))
                .andExpect(status().isCreated());

        // 차순위 수락자 앞으로 새 주문이 생긴다
        assertThat(orderRepository.findAll())
                .filteredOn(o -> o.getStatus() == OrderStatus.PAYMENT_PENDING)
                .hasSize(1);
    }

    @Test
    @DisplayName("미결제 페널티가 쌓이면 입찰이 차단된다")
    void 미결제_페널티가_입찰을_막는다() throws Exception {
        Long auctionId = openAuction(registerProduct(), LocalDateTime.now().plusHours(2));
        placeBid(auctionId, buyerId, 105_000L, "e2e-penalty-1");

        Order order = endAuctionAndSettle(auctionId);
        ReflectionTestUtils.setField(order, "paymentDeadline", LocalDateTime.now().minusMinutes(1));
        orderRepository.saveAndFlush(order);
        orderExpirationService.expireIfDue(order.getId());

        // 페널티가 기록됐는지 확인한다
        mockMvc.perform(get("/api/me/penalties").header("X-User-Id", buyerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.noShowCount").value(1));

        // 제한 기간 중이면 새 경매에도 입찰할 수 없다
        Long otherAuctionId = openAuction(registerProduct(), LocalDateTime.now().plusHours(2));
        mockMvc.perform(post("/api/auctions/{id}/bids", otherAuctionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", buyerId)
                        .header("Idempotency-Key", "e2e-penalty-blocked")
                        .content("{\"amount\": 105000}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value(40302));
    }

    // ------------------------------------------------------------------
    // 3. 트랙 사이 이음매
    // ------------------------------------------------------------------

    @Test
    @DisplayName("입찰·조회가 추천 취향 데이터로 이어진다")
    void 거래_행동이_추천으로_이어진다() throws Exception {
        Long auctionId = openAuction(registerProduct(), LocalDateTime.now().plusHours(2));

        mockMvc.perform(get("/api/auctions/{id}", auctionId).header("X-User-Id", buyerId));
        mockMvc.perform(post("/api/auctions/{id}/likes", auctionId).header("X-User-Id", buyerId))
                .andExpect(status().isOk());
        placeBid(auctionId, buyerId, 105_000L, "e2e-taste-1");

        // 조회 + 찜 + 입찰 = 3건. 개인화 전환 기준을 넘긴다.
        assertThat(activityLogRepository.countByUserId(buyerId)).isEqualTo(3);

        mockMvc.perform(get("/api/recommendations/auctions?limit=10").header("X-User-Id", buyerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.personalized").value(true));
    }

    @Test
    @DisplayName("경매가 끝나면 추천 후보에서 빠진다")
    void 끝난_경매는_추천되지_않는다() throws Exception {
        Long auctionId = openAuction(registerProduct(), LocalDateTime.now().plusHours(2));

        mockMvc.perform(get("/api/recommendations/auctions?limit=10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1));

        endAuctionAndSettle(auctionId);

        // 끝난 경매를 추천해봐야 입찰할 수 없다
        mockMvc.perform(get("/api/recommendations/auctions?limit=10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0));
    }
}
