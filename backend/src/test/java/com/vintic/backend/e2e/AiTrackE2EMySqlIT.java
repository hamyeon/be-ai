package com.vintic.backend.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.ai.search.embedding.EmbeddingClient;
import com.vintic.backend.ai.vision.dto.ConditionGrade;
import com.vintic.backend.ai.vision.dto.VisionAnalysisResult;
import com.vintic.backend.ai.vision.service.VisionAnalysisService;
import com.vintic.backend.analyze.domain.AnalysisStatus;
import com.vintic.backend.analyze.domain.ProductAnalysisSession;
import com.vintic.backend.analyze.domain.ProductAnalysisSessionRepository;
import com.vintic.backend.analyze.queue.AnalysisTaskProducer;
import com.vintic.backend.analyze.service.S3UploaderService;
import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.domain.AuctionStatus;
import com.vintic.backend.auction.repository.AuctionRepository;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// AI·조회 트랙의 플로우를 실제 MySQL에 붙여 끝까지 이어본다.
//
// 단위 테스트는 각 조각이 혼자 있을 때 도는지를 본다. 이 테스트는 조각을 이어붙였을 때
// 깨지지 않는지를 본다. #49에서 겪은 두 결함(벡터 컬럼이 TINYBLOB으로 생성돼 저장이
// 계속 실패한 것, 벡터 실패가 상품 등록을 롤백시킨 것)은 둘 다 단위 테스트가 전부
// 초록불인 상태에서 났고, 로컬 DB에 붙여봐서야 발견됐다.
//
// 무엇을 목으로 두는가:
//   OpenAI(Vision/임베딩)와 S3는 목으로 둔다. 외부 서비스라 결과가 매번 달라지고 유료다.
//   그 경계 안쪽 - 상태 전이, 트랜잭션, 스키마, 정렬 - 이 이 테스트의 관심사다.
//
// 다루지 않는 것:
//   경매 등록/낙찰/결제는 API가 아직 없어 여기서 검증할 수 없다. 경매는 리포지토리로
//   직접 만든다. 해당 API가 생기면 이 테스트 뒤에 이어 붙인다.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Testcontainers
class AiTrackE2EMySqlIT {

    // @Transactional을 일부러 쓰지 않는다.
    //
    // 테스트를 트랜잭션으로 감싸 롤백시키면 편하지만, 그러면 커밋 시점에 일어나는 일이
    // 전부 가려진다. 이 테스트가 잡으려는 결함(컬럼 타입이 맞지 않아 INSERT가 실패하는 것,
    // REQUIRES_NEW로 분리한 트랜잭션이 실제로 따로 커밋되는지)이 정확히 커밋 시점의
    // 문제라서, 롤백 방식으로는 아무것도 못 잡는다.
    //
    // 대신 각 테스트 전에 데이터를 직접 지운다.

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
    private ProductVectorRepository productVectorRepository;

    @Autowired
    private UserActivityLogRepository activityLogRepository;

    @Autowired
    private ProductAnalysisSessionRepository sessionRepository;

    @Autowired
    private com.vintic.backend.recommendation.service.ProductVectorService productVectorService;

    @Autowired
    private org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    // --- 외부 경계는 목으로 둔다 ---

    @MockitoBean
    private EmbeddingClient embeddingClient;

    @MockitoBean
    private VisionAnalysisService visionAnalysisService;

    @MockitoBean
    private S3UploaderService s3UploaderService;

    @MockitoBean
    private AnalysisTaskProducer analysisTaskProducer;

    private Long sellerId;
    private Long buyerId;

    @BeforeEach
    void setUp() {
        // FK를 참조하는 쪽부터 지운다.
        // 캐시가 테스트 간에 새지 않도록 비운다. 앞 테스트의 목록이 남아 있으면
        // 다음 테스트가 DB가 아니라 캐시를 읽는다.
        redisTemplate.keys("cache:recommendation-fallback*").forEach(redisTemplate::delete);

        activityLogRepository.deleteAll();
        productVectorRepository.deleteAll();
        auctionRepository.deleteAll();
        productRepository.deleteAll();
        userRepository.deleteAll();
        sessionRepository.deleteAll();

        sellerId = userRepository.save(User.register("seller@vintic.local", "seller", null)).getId();
        buyerId = userRepository.save(User.register("buyer@vintic.local", "buyer", null)).getId();
        // 임베딩은 유료 호출이라 목으로 둔다. 값은 브랜드마다 다르게 줘서 정렬이 실제로
        // 유사도를 따라가는지 확인할 수 있게 한다.
        when(embeddingClient.embed(anyString())).thenAnswer(invocation -> {
            String text = invocation.getArgument(0);
            return text.contains("Nike") ? vectorOf(1.0f, 0.0f) : vectorOf(0.0f, 1.0f);
        });
    }

    private float[] vectorOf(float x, float y) {
        float[] vector = new float[1536];
        vector[0] = x;
        vector[1] = y;
        return vector;
    }

    private String productJson(String brand, String model, String color) {
        return """
                {
                  "imageUrls": ["https://example.com/a.jpg","https://example.com/b.jpg","https://example.com/c.jpg"],
                  "brand": "%s", "modelName": "%s", "color": "%s", "size": 270,
                  "conditionGrade": "A", "componentStatus": "FULL",
                  "recommendedPrice": 180000, "baseMarketPrice": 185000,
                  "priceRange": "170,000원 ~ 190,000원", "sellingPrice": 180000,
                  "reason": "E2E", "sellerDescription": "E2E"
                }
                """.formatted(brand, model, color);
    }

    private Long registerProduct(String brand, String model, String color) throws Exception {
        String body = mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", sellerId)
                        .content(productJson(brand, model, color)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("id").asLong();
    }

    private Long openAuction(Long productId) {
        Product product = productRepository.findById(productId).orElseThrow();
        Auction auction = Auction.schedule(product, 100_000L, 5_000L,
                LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(3));
        ReflectionTestUtils.setField(auction, "status", AuctionStatus.LIVE);
        return auctionRepository.save(auction).getId();
    }

    // ------------------------------------------------------------------
    // 1. 상품 등록 → 벡터 생성 → 행동 로그 → 개인화 추천
    // ------------------------------------------------------------------

    @Test
    @DisplayName("상품을 등록하면 추천용 벡터가 실제 컬럼에 저장된다")
    void 상품_등록이_벡터_생성까지_이어진다() throws Exception {
        Long productId = registerProduct("Nike", "Dunk Low", "Panda");

        // 단위 테스트는 리포지토리를 목으로 대체해 DDL 경로를 지나지 않는다.
        // 컬럼이 실제로 6,144바이트를 받아내는지는 진짜 DB에 붙어야 알 수 있다.
        assertThat(productVectorRepository.findById(productId)).isPresent();
        assertThat(productVectorRepository.findById(productId).orElseThrow().getDimension())
                .isEqualTo(1536);
    }

    @Test
    @DisplayName("벡터 생성이 실패해도 상품 등록은 성공한다")
    void 벡터_실패가_상품_등록을_막지_않는다() throws Exception {
        // 추천은 부가 기능이다. 임베딩이 죽었다고 판매자가 상품을 못 올리면 안 된다.
        when(embeddingClient.embed(anyString())).thenThrow(new RuntimeException("OpenAI 장애"));

        Long productId = registerProduct("Nike", "Dunk Low", "Panda");

        assertThat(productRepository.findById(productId)).isPresent();
        assertThat(productVectorRepository.findById(productId)).isEmpty();
    }

    @Test
    @DisplayName("조회 이력이 쌓이면 추천이 개인화로 전환된다")
    void 행동_로그가_개인화_추천으로_이어진다() throws Exception {
        Long nikeProduct = registerProduct("Nike", "Dunk Low", "Panda");
        Long adidasProduct = registerProduct("Adidas", "Samba OG", "Cloud White");
        Long nikeAuction = openAuction(nikeProduct);
        Long adidasAuction = openAuction(adidasProduct);

        // 개인화 전환 기준은 행동 3건이다(ADR 6번).
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/api/auctions/{id}", nikeAuction).header("X-User-Id", buyerId))
                    .andExpect(status().isOk());
        }
        assertThat(activityLogRepository.countByUserId(buyerId)).isEqualTo(3);

        mockMvc.perform(get("/api/recommendations/auctions?limit=10").header("X-User-Id", buyerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.personalized").value(true))
                // 나이키만 본 유저에게는 나이키가 먼저 와야 한다
                .andExpect(jsonPath("$.data.items[0].auctionId").value(nikeAuction))
                .andExpect(jsonPath("$.data.items[0].similarity").isNumber())
                // 아디다스도 후보에는 들어가되 뒤로 밀려야 한다
                .andExpect(jsonPath("$.data.items[1].auctionId").value(adidasAuction));
    }

    // ------------------------------------------------------------------
    // 2. 실패·경계 케이스
    // ------------------------------------------------------------------

    @Test
    @DisplayName("벡터가 없는 상품도 개인화 추천에서 사라지지 않고, 백필 후에는 순위를 받는다")
    void 벡터_없는_상품이_추천에서_사라지지_않는다() throws Exception {
        Long nikeProduct = registerProduct("Nike", "Dunk Low", "Panda");
        Long nikeAuction = openAuction(nikeProduct);

        // 임베딩이 실패한 상품 - 벡터 없이 등록된다 (등록 자체는 성공해야 한다)
        when(embeddingClient.embed(contains("Adidas"))).thenThrow(new RuntimeException("OpenAI 장애"));
        Long adidasProduct = registerProduct("Adidas", "Samba OG", "Cloud White");
        Long adidasAuction = openAuction(adidasProduct);
        assertThat(productVectorRepository.findById(adidasProduct)).isEmpty();

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/api/auctions/{id}", nikeAuction).header("X-User-Id", buyerId))
                    .andExpect(status().isOk());
        }

        // 벡터 없는 경매가 목록에서 사라지지 않는다. 순위만 뒤로 밀리고 similarity는 null이다.
        mockMvc.perform(get("/api/recommendations/auctions?limit=10").header("X-User-Id", buyerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.personalized").value(true))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].auctionId").value(nikeAuction))
                .andExpect(jsonPath("$.data.items[1].auctionId").value(adidasAuction))
                .andExpect(jsonPath("$.data.items[1].similarity").doesNotExist());

        // 임베딩이 복구된 뒤 백필이 구멍을 메운다
        org.mockito.Mockito.reset(embeddingClient);
        when(embeddingClient.embed(anyString())).thenAnswer(invocation -> {
            String text = invocation.getArgument(0);
            return text.contains("Nike") ? vectorOf(1.0f, 0.0f) : vectorOf(0.0f, 1.0f);
        });

        List<com.vintic.backend.product.domain.Product> targets = productVectorRepository
                .findProductsWithoutVector(org.springframework.data.domain.PageRequest.of(0, 10));
        assertThat(targets).extracting(com.vintic.backend.product.domain.Product::getId)
                .containsExactly(adidasProduct);
        productVectorService.refreshAll(targets);

        // 이제 순위를 받는다
        mockMvc.perform(get("/api/recommendations/auctions?limit=10").header("X-User-Id", buyerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[1].similarity").isNumber());
    }

    @Test
    @DisplayName("Cold Start: 행동이 없으면 Fallback으로 응답한다")
    void 행동이_없는_유저는_Fallback을_받는다() throws Exception {
        Long productId = registerProduct("Nike", "Dunk Low", "Panda");
        openAuction(productId);

        mockMvc.perform(get("/api/recommendations/auctions?limit=10").header("X-User-Id", buyerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.personalized").value(false))
                // Fallback도 정상 응답이다. 데이터가 부족하다고 에러를 내지 않는다.
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items[0].similarity").doesNotExist());
    }

    @Test
    @DisplayName("Fallback 목록은 캐시되고, 입찰이 들어오면 비워진다")
    void Fallback_캐시가_입찰에_무효화된다() throws Exception {
        Long productId = registerProduct("Nike", "Dunk Low", "Panda");
        Long auctionId = openAuction(productId);

        // 1) 첫 호출 - DB를 읽고 캐시에 넣는다
        mockMvc.perform(get("/api/recommendations/auctions?limit=10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].currentPrice").value(100000));

        // 2) 캐시에 값이 들어갔는지 확인한다. 키는 "cache:" 네임스페이스 아래에 있다.
        assertThat(redisTemplate.keys("cache:recommendation-fallback*")).isNotEmpty();

        // 3) 입찰이 들어오면 현재가가 바뀌므로 캐시가 낡는다
        mockMvc.perform(post("/api/auctions/{id}/bids", auctionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", buyerId)
                        .header("Idempotency-Key", "e2e-cache-evict")
                        .content("{\"amount\": 105000}"))
                .andExpect(status().isCreated());

        assertThat(redisTemplate.keys("cache:recommendation-fallback*")).isEmpty();

        // 4) 다시 부르면 갱신된 현재가가 나온다. 캐시가 안 비워졌다면 100000이 그대로 나온다.
        mockMvc.perform(get("/api/recommendations/auctions?limit=10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].currentPrice").value(105000));
    }

    @Test
    @DisplayName("비로그인 요청도 Fallback으로 응답한다")
    void 비로그인_요청도_추천을_받는다() throws Exception {
        Long productId = registerProduct("Nike", "Dunk Low", "Panda");
        openAuction(productId);

        mockMvc.perform(get("/api/recommendations/auctions?limit=10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.personalized").value(false));
    }

    @Test
    @DisplayName("시세 부족: 매칭 데이터가 없으면 0원과 사유를 반환한다")
    void 시세가_없으면_에러_대신_사유를_돌려준다() throws Exception {
        Long analysisId = awaitingConfirmationSession();

        // 참조 CSV에 없는 브랜드다. 요청 자체는 정상이므로 200이어야 한다.
        String body = """
                {"analysisId": %d, "brand": "존재하지않는브랜드", "modelName": "없는모델",
                 "color": "없는색", "size": 270, "conditionGrade": "A", "componentStatus": "FULL"}
                """.formatted(analysisId);

        mockMvc.perform(post("/api/products/calculate-price")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.recommendedPrice").value(0))
                .andExpect(jsonPath("$.data.priceRange").value("시세 정보 없음"))
                .andExpect(jsonPath("$.data.reason").isNotEmpty());
    }

    @Test
    @DisplayName("가격 계산은 세션당 1회만 가능하다")
    void 같은_세션으로_두_번_계산하면_거부된다() throws Exception {
        Long analysisId = awaitingConfirmationSession();
        String body = """
                {"analysisId": %d, "brand": "Nike", "modelName": "Dunk Low", "color": "Panda",
                 "size": 270, "conditionGrade": "A", "componentStatus": "FULL"}
                """.formatted(analysisId);

        mockMvc.perform(post("/api/products/calculate-price")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());

        mockMvc.perform(post("/api/products/calculate-price")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(40003));
    }

    @Test
    @DisplayName("존재하지 않는 분석 세션은 40402를 반환한다")
    void 없는_세션을_조회하면_40402다() throws Exception {
        // #46에서 경매 쪽이 40401로 옮겨오면서 번호가 겹쳐 40402로 분리했다.
        mockMvc.perform(get("/api/products/analyze/{taskId}", 999_999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value(40402));
    }

    @Test
    @DisplayName("없는 경로는 500이 아니라 404를 반환한다")
    void 오타난_경로는_404다() throws Exception {
        // 차단된 것과 서버가 죽은 것이 응답으로 구분돼야 한다.
        mockMvc.perform(get("/api/auctionss/1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value(40400));
    }

    /** 가격 계산이 가능한 상태(AWAITING_USER_CONFIRMATION)의 세션을 만든다. */
    private Long awaitingConfirmationSession() throws Exception {
        ProductAnalysisSession session = ProductAnalysisSession.create();
        session.markImageUploaded(List.of("https://example.com/a.jpg"));
        session.markQueued();
        session.startVisionProcessing();
        session.completeVision(objectMapper.writeValueAsString(Map.of("brand", "Nike")));
        return sessionRepository.save(session).getId();
    }

    @Test
    @DisplayName("AI 분석이 실패하면 세션이 VISION_FAILED로 남는다")
    void 분석_실패가_상태로_기록된다() throws Exception {
        ProductAnalysisSession session = ProductAnalysisSession.create();
        session.markImageUploaded(List.of("https://example.com/a.jpg"));
        session.markQueued();
        session.startVisionProcessing();
        session.failVision("OpenAI Vision API 호출 중 오류가 발생했습니다.");
        Long analysisId = sessionRepository.save(session).getId();

        // 분석 작업의 실패와 API 요청의 실패는 다르다. 조회 자체는 성공(200)이고,
        // 실패 여부는 status/failureStage로 전달한다.
        mockMvc.perform(get("/api/products/analyze/{taskId}", analysisId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value(AnalysisStatus.VISION_FAILED.name()))
                .andExpect(jsonPath("$.data.failureStage").value("VISION"))
                .andExpect(jsonPath("$.data.failureMessage").isNotEmpty());
    }

    @Test
    @DisplayName("Vision 결과 DTO가 응답 필드로 그대로 이어진다")
    void 분석_완료_결과가_응답에_실린다() throws Exception {
        VisionAnalysisResult result = new VisionAnalysisResult(
                "Nike", "Dunk Low", "Panda", 270, "앞코 주름", ConditionGrade.B, true,
                0.9, false, List.of(), List.of(), List.of(), List.of());

        ProductAnalysisSession session = ProductAnalysisSession.create();
        session.markImageUploaded(List.of("https://example.com/a.jpg"));
        session.markQueued();
        session.startVisionProcessing();
        session.completeVision(objectMapper.writeValueAsString(result));
        Long analysisId = sessionRepository.save(session).getId();

        mockMvc.perform(get("/api/products/analyze/{taskId}", analysisId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status")
                        .value(AnalysisStatus.AWAITING_USER_CONFIRMATION.name()))
                .andExpect(jsonPath("$.data.brand").value("Nike"))
                .andExpect(jsonPath("$.data.conditionGrade").value("B"));
    }
}
