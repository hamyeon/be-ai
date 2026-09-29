package com.vintic.backend.purchasegoal;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.auction.service.AuctionEndService;
import com.vintic.backend.autobid.repository.AutoBidSettingRepository;
import com.vintic.backend.autobid.service.AutoBidCommandService;
import com.vintic.backend.common.dto.ApiResponse;
import com.vintic.backend.notification.domain.NotificationType;
import com.vintic.backend.notification.repository.NotificationRepository;
import com.vintic.backend.product.domain.Product;
import com.vintic.backend.product.repository.ProductRepository;
import com.vintic.backend.purchasegoal.domain.PurchaseGoal;
import com.vintic.backend.purchasegoal.domain.PurchaseGoalStatus;
import com.vintic.backend.purchasegoal.dto.CreatePurchaseGoalRequest;
import com.vintic.backend.purchasegoal.dto.PurchaseGoalDetailResponse;
import com.vintic.backend.purchasegoal.dto.PurchaseGoalMatchHistoryResponse;
import com.vintic.backend.purchasegoal.dto.PurchaseGoalParticipationResponse;
import com.vintic.backend.purchasegoal.dto.PurchaseGoalResponse;
import com.vintic.backend.purchasegoal.repository.PurchaseGoalRepository;
import com.vintic.backend.purchasegoal.service.PurchaseGoalScanScheduler;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

// Day 8: 실제 등록 API -> 스캔(Agent AutoBid 등록) -> 기존 정산(AuctionEndService/
// AuctionSettlementService, 수정 없이 재사용) -> Order 생성 -> Goal FULFILLED -> 조회 응답까지
// 하나로 연결한다. 패배 후 다른 경매에 재참여하는 흐름도 같은 시나리오 안에서 함께 본다 -
// 별도 테스트로 쪼개면 "같은 Goal이 두 경매를 거치며 참여 이력이 누적된다"는 그림을 놓친다.
//
// purchase-agent.matcher.provider=rule로 override해 실제 OpenAI 호출 없이 결정적으로
// Matcher까지 돈다(OPENAI_API_KEY는 컨텍스트 기동에만 필요).
//
// 실행: OPENAI_API_KEY=dummy ./gradlew test --tests "...PurchaseGoalE2EMySqlIT"
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@Testcontainers
class PurchaseGoalE2EMySqlIT {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("purchase-agent.scan.enabled", () -> "true");
        registry.add("purchase-agent.matcher.provider", () -> "rule");
        registry.add("auction.lifecycle.start.enabled", () -> "false");
        registry.add("auction.lifecycle.end.enabled", () -> "false");
        registry.add("payment.expiration.enabled", () -> "false");
        registry.add("backup-offer.expiration.enabled", () -> "false");
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private PurchaseGoalScanScheduler scanScheduler;

    @Autowired
    private AuctionEndService auctionEndService;

    @Autowired
    private AutoBidCommandService autoBidCommandService;

    @Autowired
    private PurchaseGoalRepository purchaseGoalRepository;

    @Autowired
    private AuctionRepository auctionRepository;

    @Autowired
    private AutoBidSettingRepository autoBidSettingRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private UserRepository userRepository;

    private User persistUser(String email) {
        return userRepository.save(User.register(email, email, null));
    }

    // 실제 시세 데이터(New Balance/nb990, used_market_prices.csv)를 그대로 쓴다 - Day 3~7과 동일.
    private Product persistProduct(User seller) {
        return productRepository.save(new Product(
                seller, List.of("https://example.com/a.jpg"),
                "New Balance", "990v6", "Grey", 270, "A", "PARTIAL",
                300000, 350000, "", 290000, "", ""
        ));
    }

    private Auction persistLiveAuction(Product product, LocalDateTime endAt) {
        Auction auction = Auction.schedule(product, 1000L, 500L, LocalDateTime.now().minusMinutes(10), endAt);
        auction.start();
        return auctionRepository.save(auction);
    }

    // endIfDue()가 집어갈 수 있도록 endAt을 과거로 되돌린 뒤 실제 종료+정산 서비스를 그대로 호출한다.
    private void endAndSettle(Long auctionId) {
        Auction auction = auctionRepository.findById(auctionId).orElseThrow();
        ReflectionTestUtils.setField(auction, "endAt", LocalDateTime.now().minusSeconds(1));
        auctionRepository.save(auction);
        auctionEndService.endIfDue(auctionId);
    }

    private HttpHeaders authHeaders(Long userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-User-Id", String.valueOf(userId));
        return headers;
    }

    @Test
    void 등록_스캔_정산_낙찰_패배_재참여_조회까지_전체_흐름이_연결된다() {
        User buyer = persistUser("agent-buyer@vintic.local");
        User seller = persistUser("agent-seller@vintic.local");
        User competingBidder = persistUser("competing-bidder@vintic.local");

        // 1) 실제 등록 API로 Goal을 만든다.
        CreatePurchaseGoalRequest createRequest = new CreatePurchaseGoalRequest(
                "New Balance", "nb990", "뉴발란스 990", "B",
                null, 200000L, null, OffsetDateTime.now().plusDays(7)
        );
        ResponseEntity<ApiResponse<PurchaseGoalResponse>> createResponse = restTemplate.exchange(
                "/api/purchase-goals", HttpMethod.POST,
                new HttpEntity<>(createRequest, authHeaders(buyer.getId())),
                new ParameterizedTypeReference<>() {
                }
        );
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Long goalId = createResponse.getBody().data().id();

        // 2) auctionA를 스캔이 찾아 Agent가 AutoBid를 등록한다(Day 3~5, 실제 규칙 기반 Matcher 경유).
        Auction auctionA = persistLiveAuction(persistProduct(seller), LocalDateTime.now().plusHours(2));
        scanScheduler.scan();

        PurchaseGoal afterEngageA = purchaseGoalRepository.findById(goalId).orElseThrow();
        assertThat(afterEngageA.getStatus()).isEqualTo(PurchaseGoalStatus.ENGAGED);
        assertThat(afterEngageA.getCurrentAuctionId()).isEqualTo(auctionA.getId());
        assertThat(autoBidSettingRepository.findByAuctionIdAndUserIdAndActiveSlotTrue(auctionA.getId(), buyer.getId()))
                .hasValueSatisfying(setting -> assertThat(setting.getPurchaseGoalId()).isEqualTo(goalId));

        // 3) 다른 사용자가 Agent의 cap보다 훨씬 높은 AutoBid를 등록해 auctionA의 실제 낙찰자가 된다
        //    (기존 AutoBid/Proxy 경로를 그대로 태운다 - Agent 쪽 코드는 건드리지 않는다).
        autoBidCommandService.createAutoBid(auctionA.getId(), competingBidder.getId(), 500000L);

        // 4) 기존 정산 경로(AuctionEndService.endIfDue -> AuctionSettlementService.settle)를 그대로
        //    호출한다 - Agent 전용 코드가 아니다. 승자는 competingBidder이므로 buyer의 Order는 없다.
        endAndSettle(auctionA.getId());

        // 5) auctionB를 미리 준비해두고 스캔을 한 번 더 돌린다 - 같은 tick 안에서 패배 관찰(ACTIVE
        //    복귀) 후 새 후보를 찾아 재참여해야 한다(Day 6).
        //    ProxyPriceEngine(#42)은 currentWinner가 없는 LIVE 경매에 AutoBid가 "혼자" 등록되면
        //    승자를 정하지 않는다(ProxyTrigger.Auto는 기존 winner가 있을 때만 phantom을 만든다 -
        //    "예약자 1명도 최소 한 단계는 응찰" 규칙은 ProxyTrigger.None 전용이고, 그건 AuctionStart의
        //    RESERVED 일괄 전환 경로에서만 쓰인다). 그래서 Agent가 유일한 입찰자면 정산해도 낙찰자가
        //    없다 - 실제 낙찰을 재현하려면 Agent보다 훨씬 낮은 cap의 다른 입찰자가 먼저 있어야
        //    진짜 2자 경쟁으로 승자가 정해진다. Agent 코드는 건드리지 않고 순수 기존 AutoBid 경로만 쓴다.
        User weakBidder = persistUser("weak-bidder@vintic.local");
        Auction auctionB = persistLiveAuction(persistProduct(seller), LocalDateTime.now().plusHours(2));
        autoBidCommandService.createAutoBid(auctionB.getId(), weakBidder.getId(), auctionB.getMinNextBidAmount());
        scanScheduler.scan();

        PurchaseGoal afterLossAndReengage = purchaseGoalRepository.findById(goalId).orElseThrow();
        assertThat(afterLossAndReengage.getStatus()).isEqualTo(PurchaseGoalStatus.ENGAGED);
        assertThat(afterLossAndReengage.getCurrentAuctionId()).isEqualTo(auctionB.getId());

        // 6) 이번엔 경쟁자 없이 auctionB를 정산한다 - buyer가 유일한 입찰자라 낙찰된다.
        endAndSettle(auctionB.getId());

        // 7) 마지막 스캔이 낙찰을 관찰해 FULFILLED로 끝낸다.
        scanScheduler.scan();

        // 8) 실제 조회 API로 최종 상태를 확인한다.
        ResponseEntity<ApiResponse<PurchaseGoalDetailResponse>> detailResponse = restTemplate.exchange(
                "/api/purchase-goals/" + goalId, HttpMethod.GET,
                new HttpEntity<>(authHeaders(buyer.getId())),
                new ParameterizedTypeReference<>() {
                }
        );
        assertThat(detailResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        PurchaseGoalDetailResponse detail = detailResponse.getBody().data();
        assertThat(detail.status()).isEqualTo("FULFILLED");
        assertThat(detail.participationCount()).isEqualTo(2);
        assertThat(detail.wonCount()).isEqualTo(1);
        assertThat(detail.participations())
                .extracting(PurchaseGoalParticipationResponse::auctionId, PurchaseGoalParticipationResponse::status)
                .containsExactlyInAnyOrder(
                        tuple(auctionA.getId(), "LOST"),
                        tuple(auctionB.getId(), "WON")
                );

        ResponseEntity<ApiResponse<List<PurchaseGoalMatchHistoryResponse>>> matchResponse = restTemplate.exchange(
                "/api/purchase-goals/" + goalId + "/matches", HttpMethod.GET,
                new HttpEntity<>(authHeaders(buyer.getId())),
                new ParameterizedTypeReference<>() {
                }
        );
        assertThat(matchResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<PurchaseGoalMatchHistoryResponse> matches = matchResponse.getBody().data();
        assertThat(matches).extracting(PurchaseGoalMatchHistoryResponse::auctionId)
                .contains(auctionA.getId(), auctionB.getId());
        assertThat(matches).allMatch(PurchaseGoalMatchHistoryResponse::matched);

        // 9) buyer 입장에서 AUCTION_WON은 정확히 한 번만 생성됐어야 한다(auctionA는 competingBidder가
        //    낙찰했으므로 buyer에게는 알림이 없다) - Day 7의 "중복 생성 안 함" 단위 테스트와 달리
        //    실제 등록/스캔/정산 전체 흐름을 통과시켜 확인한다.
        long buyerAuctionWonCount = notificationRepository.findAll().stream()
                .filter(n -> n.getRecipient().getId().equals(buyer.getId()))
                .filter(n -> n.getType() == NotificationType.AUCTION_WON)
                .count();
        assertThat(buyerAuctionWonCount).isEqualTo(1);

        long buyerEngagedCount = notificationRepository.findAll().stream()
                .filter(n -> n.getRecipient().getId().equals(buyer.getId()))
                .filter(n -> n.getType() == NotificationType.PURCHASE_AGENT_ENGAGED)
                .count();
        assertThat(buyerEngagedCount).isEqualTo(2);

        long buyerLostCount = notificationRepository.findAll().stream()
                .filter(n -> n.getRecipient().getId().equals(buyer.getId()))
                .filter(n -> n.getType() == NotificationType.PURCHASE_AGENT_LOST)
                .count();
        assertThat(buyerLostCount).isEqualTo(1);
    }
}
