package com.vintic.backend.concurrency;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.auth.jwt.JwtTokenProvider;
import com.vintic.backend.backupoffer.domain.BackupOffer;
import com.vintic.backend.backupoffer.repository.BackupOfferRepository;
import com.vintic.backend.bid.domain.Bid;
import com.vintic.backend.bid.domain.BidType;
import com.vintic.backend.bid.repository.BidRepository;
import com.vintic.backend.notification.domain.Notification;
import com.vintic.backend.notification.domain.NotificationType;
import com.vintic.backend.notification.repository.NotificationRepository;
import com.vintic.backend.order.domain.Order;
import com.vintic.backend.order.repository.OrderRepository;
import com.vintic.backend.penalty.domain.Penalty;
import com.vintic.backend.penalty.repository.PenaltyRepository;
import com.vintic.backend.product.domain.Product;
import com.vintic.backend.product.repository.ProductRepository;
import com.vintic.backend.user.domain.User;
import com.vintic.backend.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// #75-4E: "MockAuth에서 검증하던 기존 userId 기반 business rule이 실제 JWT principal에서도
// 동일하게 동작하는가"만 확인한다 - business logic 자체를 다시 증명하지 않는다. 실제
// JwtTokenProvider로 발급한 Access Token을 Authorization: Bearer로 실제 HTTP 요청에 실어
// dev SecurityFilterChain을 그대로 통과시킨다. X-User-Id는 (보낸다면) 항상 무시되는지
// 확인하는 용도로만 등장한다 - production identity source로 쓰지 않는다.
//
// dev profile을 활성화해야 JwtSecurityConfig/JwtAuthenticationFilter/AuthController 등이
// 뜬다. lifecycle/payment/backup-offer expiration scheduler는 application-dev.yml이 전부
// enabled=true로 켜두므로, 이 테스트의 수동 fixture(직접 만든 ENDED Auction/PAYMENT_PENDING
// Order/WAITING BackupOffer 등)를 스케줄러가 백그라운드에서 건드리지 않도록 명시적으로
// 다시 꺼둔다(#57-2가 이미 겪은 문제와 동일한 이유).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@Testcontainers
class JwtAuthorizationWiringMySqlIT {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("jwt.secret", () -> "jwt-wiring-regression-it-secret-32-bytes-minimum!!");
        registry.add("auction.lifecycle.start.enabled", () -> "false");
        registry.add("auction.lifecycle.end.enabled", () -> "false");
        registry.add("payment.expiration.enabled", () -> "false");
        registry.add("backup-offer.expiration.enabled", () -> "false");
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private AuctionRepository auctionRepository;

    @Autowired
    private BidRepository bidRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private BackupOfferRepository backupOfferRepository;

    @Autowired
    private PenaltyRepository penaltyRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ---- fixture helpers ----

    private User persistUser(String label) {
        return userRepository.save(User.register(label + "-" + System.nanoTime() + "@vintic.local", label, null));
    }

    private Product persistProduct(User seller) {
        return productRepository.save(new Product(
                seller,
                List.of("https://example.com/a.jpg"),
                "Nike", "Dunk Low", "Panda", 270, "B", "PARTIAL",
                300000, 350000, "285,000원 ~ 315,000원", 290000, "사유", "설명"
        ));
    }

    private Auction persistLiveAuction(Product product) {
        Auction auction = Auction.schedule(
                product, 10000L, 5000L, LocalDateTime.now().minusMinutes(30), LocalDateTime.now().plusHours(1)
        );
        auction.start();
        return auctionRepository.save(auction);
    }

    // 입찰까지 반영한 뒤 마지막에 한 번만 save한다 - 중간에 save한 엔티티를 계속 mutate만 하면
    // (재저장 없이) DB에는 반영되지 않는다는 것을 다른 기존 IT로 확인했다(AuctionForfeitAtomicityMySqlIT는
    // 이 재저장이 필요 없는 필드만 검증해서 드러나지 않았을 뿐이다) - 그래서 여기서는 반드시
    // bid마다 Bid row를 저장한 뒤 auction 쪽도 마지막에 다시 save해 currentPrice/currentWinner까지
    // 실제로 flush시킨다.
    private Auction persistEndedAuctionWithBids(Product product, User... biddersLowToHigh) {
        Auction auction = Auction.schedule(
                product, 10000L, 5000L, LocalDateTime.now().minusHours(2), LocalDateTime.now().minusHours(1)
        );
        auction.start();
        Auction saved = auctionRepository.save(auction);
        long amount = 15000L;
        for (User bidder : biddersLowToHigh) {
            bidRepository.save(Bid.place(saved, bidder, amount, BidType.MANUAL));
            saved.placeManualBid(bidder, amount);
            amount += 5000L;
        }
        saved.end();
        return auctionRepository.save(saved);
    }

    private HttpHeaders bearer(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtTokenProvider.issueAccessToken(user.getId()));
        return headers;
    }

    private HttpHeaders bearerWithForeignXUserId(User jwtUser, User foreignUser) {
        HttpHeaders headers = bearer(jwtUser);
        headers.set("X-User-Id", String.valueOf(foreignUser.getId()));
        return headers;
    }

    private JsonNode getJson(String url, HttpHeaders headers) {
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
        return parse(response);
    }

    private JsonNode postJson(String url, HttpHeaders headers, String body) {
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        return parse(response);
    }

    private JsonNode patchJson(String url, HttpHeaders headers, String body) {
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.PATCH, new HttpEntity<>(body, headers), String.class);
        return parse(response);
    }

    private JsonNode parse(ResponseEntity<String> response) {
        try {
            JsonNode node = objectMapper.readTree(response.getBody());
            return ((com.fasterxml.jackson.databind.node.ObjectNode) node).put("__status", response.getStatusCode().value());
        } catch (Exception e) {
            throw new IllegalStateException("응답 파싱 실패: " + response.getBody(), e);
        }
    }

    private int status(JsonNode node) {
        return node.path("__status").asInt();
    }

    // ==================== 2. Manual Bid ====================

    @Test
    void ManualBid_buyer는_자신의_JWT로_정상_입찰한다() {
        User seller = persistUser("seller");
        User buyer = persistUser("buyer");
        Auction auction = persistLiveAuction(persistProduct(seller));

        JsonNode response = postJson(
                "/api/auctions/" + auction.getId() + "/bids",
                withIdempotencyKey(bearer(buyer)),
                "{\"amount\":15000}"
        );

        assertThat(status(response)).isEqualTo(201);
        assertThat(response.path("data").path("submittedAmount").asLong()).isEqualTo(15000L);
    }

    @Test
    void ManualBid_판매자_본인_JWT로_입찰하면_40301이_유지된다() {
        User seller = persistUser("seller");
        Auction auction = persistLiveAuction(persistProduct(seller));

        JsonNode response = postJson(
                "/api/auctions/" + auction.getId() + "/bids",
                withIdempotencyKey(bearer(seller)),
                "{\"amount\":15000}"
        );

        assertThat(status(response)).isEqualTo(403);
        assertThat(response.path("error").path("code").asInt()).isEqualTo(40301);
    }

    @Test
    void ManualBid_다른_사람의_X_User_Id_헤더를_보내도_JWT_identity가_우선한다() {
        User seller = persistUser("seller");
        User buyer = persistUser("buyer");
        User otherUser = persistUser("other");
        Auction auction = persistLiveAuction(persistProduct(seller));

        JsonNode response = postJson(
                "/api/auctions/" + auction.getId() + "/bids",
                withIdempotencyKey(bearerWithForeignXUserId(buyer, otherUser)),
                "{\"amount\":15000}"
        );

        assertThat(status(response)).isEqualTo(201);
        Bid recordedBid = bidRepository.findByAuctionIdOrderByCreatedAtDescIdDesc(
                auction.getId(), org.springframework.data.domain.PageRequest.of(0, 1)
        ).getContent().get(0);
        assertThat(recordedBid.getUser().getId()).isEqualTo(buyer.getId());
    }

    private HttpHeaders withIdempotencyKey(HttpHeaders headers) {
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        return headers;
    }

    // ==================== 3. AutoBid ====================

    @Test
    void AutoBid_생성_조회_수정_취소가_JWT_소유자_기준으로만_동작한다() {
        User seller = persistUser("seller");
        User owner = persistUser("owner");
        User stranger = persistUser("stranger");
        Auction auction = persistLiveAuction(persistProduct(seller));
        String base = "/api/auctions/" + auction.getId() + "/auto-bids";

        JsonNode created = postJson(base, withIdempotencyKey(bearer(owner)), "{\"maxAmount\":50000}");
        assertThat(status(created)).isEqualTo(201);

        // 소유자 JWT로 조회하면 자신의 설정이 보인다.
        JsonNode ownerGet = getJson(base + "/me", bearer(owner));
        assertThat(status(ownerGet)).isEqualTo(200);
        assertThat(ownerGet.path("data").path("maxAmount").asLong()).isEqualTo(50000L);

        // 다른 User의 JWT로는 자기 설정이 없으므로 40404 - owner의 설정이 보이거나 영향받지 않는다.
        JsonNode strangerGet = getJson(base + "/me", bearer(stranger));
        assertThat(status(strangerGet)).isEqualTo(404);
        assertThat(strangerGet.path("error").path("code").asInt()).isEqualTo(40404);

        // 다른 User의 X-User-Id를 함께 보내도 owner 본인 설정만 수정된다.
        JsonNode updated = patchJson(
                base + "/me", withIdempotencyKey(bearerWithForeignXUserId(owner, stranger)), "{\"maxAmount\":60000}"
        );
        assertThat(status(updated)).isEqualTo(200);
        assertThat(updated.path("data").path("maxAmount").asLong()).isEqualTo(60000L);

        ResponseEntity<String> cancelResponse = restTemplate.exchange(
                base + "/me", HttpMethod.DELETE, new HttpEntity<>(bearer(owner)), String.class
        );
        assertThat(cancelResponse.getStatusCode().value()).isEqualTo(200);

        // stranger는 여전히 자기 설정이 없다(owner의 취소와 무관).
        JsonNode strangerGetAfter = getJson(base + "/me", bearer(stranger));
        assertThat(status(strangerGetAfter)).isEqualTo(404);
    }

    // ==================== 4. Like / personalization ====================

    @Test
    void Like_토글이_JWT_user에게_저장되고_isLiked가_JWT_기준으로_개인화된다() {
        User seller = persistUser("seller");
        User liker = persistUser("liker");
        User other = persistUser("other");
        Auction auction = persistLiveAuction(persistProduct(seller));
        String detailUrl = "/api/auctions/" + auction.getId();

        JsonNode likeResponse = postJson(detailUrl + "/likes", bearer(liker), "{}");
        assertThat(status(likeResponse)).isEqualTo(200);
        assertThat(likeResponse.path("data").path("liked").asBoolean()).isTrue();

        assertThat(getJson(detailUrl, bearer(liker)).path("data").path("isLiked").asBoolean()).isTrue();
        assertThat(getJson(detailUrl, bearer(other)).path("data").path("isLiked").asBoolean()).isFalse();

        // 토큰 없음 -> anonymous(중립값).
        assertThat(getJson(detailUrl, new HttpHeaders()).path("data").path("isLiked").asBoolean()).isFalse();

        // 토큰 없이 임의 X-User-Id만 보내도 dev에서는 완전히 무시되어 여전히 anonymous.
        HttpHeaders onlyXUserId = new HttpHeaders();
        onlyXUserId.set("X-User-Id", String.valueOf(liker.getId()));
        assertThat(getJson(detailUrl, onlyXUserId).path("data").path("isLiked").asBoolean()).isFalse();

        // 유효하지 않은 토큰을 명시적으로 보내면 anonymous 허용 endpoint여도 40101.
        HttpHeaders invalidBearer = new HttpHeaders();
        invalidBearer.setBearerAuth("this-is-not-a-valid-jwt");
        JsonNode invalidTokenResponse = getJson(detailUrl, invalidBearer);
        assertThat(status(invalidTokenResponse)).isEqualTo(401);
        assertThat(invalidTokenResponse.path("error").path("code").asInt()).isEqualTo(40101);
    }

    @Test
    void BidHistory_isMine과_myState가_JWT_기준으로_개인화된다() {
        User seller = persistUser("seller");
        User bidder = persistUser("bidder");
        User other = persistUser("other");
        Auction auction = persistLiveAuction(persistProduct(seller));
        postJson(
                "/api/auctions/" + auction.getId() + "/bids", withIdempotencyKey(bearer(bidder)), "{\"amount\":15000}"
        );

        JsonNode bidHistoryAsBidder = getJson("/api/auctions/" + auction.getId() + "/bids", bearer(bidder));
        assertThat(bidHistoryAsBidder.path("data").path("bids").get(0).path("isMine").asBoolean()).isTrue();

        JsonNode bidHistoryAsOther = getJson("/api/auctions/" + auction.getId() + "/bids", bearer(other));
        assertThat(bidHistoryAsOther.path("data").path("bids").get(0).path("isMine").asBoolean()).isFalse();

        JsonNode liveAsBidder = getJson("/api/auctions/" + auction.getId() + "/live", bearer(bidder));
        assertThat(liveAsBidder.path("data").path("isMine").asBoolean()).isTrue();

        JsonNode liveAsOther = getJson("/api/auctions/" + auction.getId() + "/live", bearer(other));
        assertThat(liveAsOther.path("data").path("isMine").asBoolean()).isFalse();
    }

    // ==================== 5. Result ====================

    @Test
    void Result가_winner와_loser_JWT마다_다르게_계산된다() {
        User seller = persistUser("seller");
        User winner = persistUser("winner");
        User loser = persistUser("loser");
        Auction auction = persistEndedAuctionWithBids(persistProduct(seller), loser, winner);
        // Result는 persisted entity가 아니라 Order/BackupOffer/Penalty로부터 매번 계산된다
        // (AuctionResultQueryService 주석 §56-0) - Auction.currentWinner만으로는 WON이 되지
        // 않고, settle()이 만드는 것과 동일하게 winner Order를 직접 만들어줘야 한다.
        orderRepository.save(Order.createForWinner(
                auction, winner, 20000L, 3000L, LocalDateTime.now().plusHours(24)
        ));
        String url = "/api/auctions/" + auction.getId() + "/result";

        JsonNode winnerResult = getJson(url, bearer(winner));
        JsonNode loserResult = getJson(url, bearer(loser));

        assertThat(winnerResult.path("data").path("result").asText()).isEqualTo("WON");
        assertThat(loserResult.path("data").path("result").asText()).isNotEqualTo("WON");
    }

    // ==================== 6. Order ownership ====================

    @Test
    void Order는_JWT_소유자만_조회_결제할_수_있다() {
        User seller = persistUser("seller");
        User buyer = persistUser("buyer");
        User stranger = persistUser("stranger");
        Auction auction = persistEndedAuctionWithBids(persistProduct(seller), buyer);
        Order order = orderRepository.save(Order.createForWinner(
                auction, buyer, 15000L, 3000L, LocalDateTime.now().plusHours(24)
        ));
        String getUrl = "/api/orders/" + order.getId();
        String payUrl = getUrl + "/pay";

        assertThat(status(getJson(getUrl, bearer(buyer)))).isEqualTo(200);

        JsonNode strangerGet = getJson(getUrl, bearer(stranger));
        assertThat(status(strangerGet)).isEqualTo(403);
        assertThat(strangerGet.path("error").path("code").asInt()).isEqualTo(40304);

        JsonNode strangerPay = postJson(payUrl, bearer(stranger), "{}");
        assertThat(status(strangerPay)).isEqualTo(403);
        assertThat(strangerPay.path("error").path("code").asInt()).isEqualTo(40304);

        JsonNode buyerPay = postJson(payUrl, bearer(buyer), "{}");
        assertThat(status(buyerPay)).isEqualTo(200);
        assertThat(buyerPay.path("data").path("status").asText()).isEqualTo("PAID");
    }

    // ==================== 7. BackupOffer ownership(40305) ====================

    @Test
    void BackupOffer는_candidate_JWT만_GET_accept_decline할_수_있다() {
        User seller = persistUser("seller");
        User candidate1 = persistUser("candidate1");
        User candidate2 = persistUser("candidate2");
        User candidate3 = persistUser("candidate3");
        User stranger = persistUser("stranger");
        Auction auction1 = persistEndedAuctionWithBids(persistProduct(seller));
        Auction auction2 = persistEndedAuctionWithBids(persistProduct(seller));
        Auction auction3 = persistEndedAuctionWithBids(persistProduct(seller));

        BackupOffer ownOffer = backupOfferRepository.save(BackupOffer.create(auction1, candidate1, 15000L));
        BackupOffer strangerTargetOffer = backupOfferRepository.save(BackupOffer.create(auction2, candidate2, 15000L));
        BackupOffer alreadyResolvedOffer = backupOfferRepository.save(BackupOffer.create(auction3, candidate3, 15000L));

        // 존재하지 않는 offer -> 404.
        JsonNode notFound = getJson("/api/backup-offers/999999999", bearer(candidate1));
        assertThat(status(notFound)).isEqualTo(404);
        assertThat(notFound.path("error").path("code").asInt()).isEqualTo(40403);

        // 존재하지만 타인 -> 40305(GET/accept/decline 전부).
        String strangerTargetBase = "/api/backup-offers/" + strangerTargetOffer.getId();
        JsonNode strangerGet = getJson(strangerTargetBase, bearer(stranger));
        assertThat(status(strangerGet)).isEqualTo(403);
        assertThat(strangerGet.path("error").path("code").asInt()).isEqualTo(40305);

        JsonNode strangerAccept = postJson(strangerTargetBase + "/accept", withIdempotencyKey(bearer(stranger)), "{}");
        assertThat(status(strangerAccept)).isEqualTo(403);
        assertThat(strangerAccept.path("error").path("code").asInt()).isEqualTo(40305);

        JsonNode strangerDecline = postJson(strangerTargetBase + "/decline", bearer(stranger), "{}");
        assertThat(status(strangerDecline)).isEqualTo(403);
        assertThat(strangerDecline.path("error").path("code").asInt()).isEqualTo(40305);

        // 본인 -> GET/accept 정상.
        String ownBase = "/api/backup-offers/" + ownOffer.getId();
        assertThat(status(getJson(ownBase, bearer(candidate1)))).isEqualTo(200);
        JsonNode ownAccept = postJson(ownBase + "/accept", withIdempotencyKey(bearer(candidate1)), "{}");
        assertThat(status(ownAccept)).isEqualTo(201);

        // 본인이지만 이미 처리된 상태에서 재요청 -> 기존 409(40912) 그대로 유지.
        JsonNode alreadyResolvedBase = postJson(
                "/api/backup-offers/" + alreadyResolvedOffer.getId() + "/accept",
                withIdempotencyKey(bearer(candidate3)), "{}"
        );
        assertThat(status(alreadyResolvedBase)).isEqualTo(201);
        JsonNode secondAccept = postJson(
                "/api/backup-offers/" + alreadyResolvedOffer.getId() + "/accept",
                withIdempotencyKey(bearer(candidate3)), "{}"
        );
        assertThat(status(secondAccept)).isEqualTo(409);
        assertThat(secondAccept.path("error").path("code").asInt()).isEqualTo(40912);
    }

    // 별도 decline 전용 fixture(accept로 소진시키지 않은 WAITING row가 필요). decline()은
    // BackupCandidateSelector로 다음 차순위를 찾기 위해 candidate가 실제 auction의 ranked bid
    // 목록에 있어야 한다(BackupOfferCommandService.createNextBackupOfferIfCandidateExists) -
    // 그래서 candidate가 실제로 rank 2가 되도록 winner도 함께 입찰시킨다.
    @Test
    void BackupOffer_decline은_candidate_JWT만_수행할_수_있다() {
        User seller = persistUser("seller");
        User winner = persistUser("winner");
        User candidate = persistUser("candidate");
        Auction auction = persistEndedAuctionWithBids(persistProduct(seller), candidate, winner);
        BackupOffer offer = backupOfferRepository.save(BackupOffer.create(auction, candidate, 15000L));

        JsonNode declined = postJson("/api/backup-offers/" + offer.getId() + "/decline", bearer(candidate), "{}");

        assertThat(status(declined)).isEqualTo(200);
        assertThat(declined.path("data").path("status").asText()).isEqualTo("DECLINED");
    }

    // ==================== 8. Penalty ====================

    @Test
    void Penalty는_JWT_userId_기준으로_본인_이력만_반환한다() {
        User userA = persistUser("penaltyA");
        User userB = persistUser("penaltyB");
        Auction auctionA = persistEndedAuctionWithBids(persistProduct(persistUser("sellerA")));
        Auction auctionB = persistEndedAuctionWithBids(persistProduct(persistUser("sellerB")));
        penaltyRepository.save(Penalty.forfeited(userA, auctionA));
        penaltyRepository.save(Penalty.forfeited(userB, auctionB));

        JsonNode responseA = getJson("/api/me/penalties", bearer(userA));
        JsonNode responseB = getJson("/api/me/penalties", bearer(userB));

        assertThat(responseA.path("data").path("penalties")).hasSize(1);
        assertThat(responseA.path("data").path("penalties").get(0).path("auctionId").asLong()).isEqualTo(auctionA.getId());
        assertThat(responseB.path("data").path("penalties")).hasSize(1);
        assertThat(responseB.path("data").path("penalties").get(0).path("auctionId").asLong()).isEqualTo(auctionB.getId());
    }

    // ==================== 9. Notification ====================

    @Test
    void Notification_목록_unreadCount_read가_JWT_userId로_격리된다() {
        User userA = persistUser("notifA");
        User userB = persistUser("notifB");
        Notification unreadForA = notificationRepository.save(Notification.create(
                userA, NotificationType.AUCTION_WON, 1L, 100L, "제목", "본문", "AUCTION_WON:100", LocalDateTime.now()
        ));
        notificationRepository.save(Notification.create(
                userB, NotificationType.AUCTION_WON, 1L, 200L, "제목", "본문", "AUCTION_WON:200", LocalDateTime.now()
        ));

        JsonNode listA = getJson("/api/notifications", bearer(userA));
        assertThat(listA.path("data").path("notifications")).hasSize(1);
        JsonNode unreadCountA = getJson("/api/notifications/unread-count", bearer(userA));
        assertThat(unreadCountA.path("data").path("unreadCount").asLong()).isEqualTo(1L);

        // 타인의 notification id로 읽음 처리 시도 -> 40405(존재/타인 구분 없이).
        JsonNode strangerRead = patchJson(
                "/api/notifications/" + unreadForA.getId() + "/read", bearer(userB), "{}"
        );
        assertThat(status(strangerRead)).isEqualTo(404);
        assertThat(strangerRead.path("error").path("code").asInt()).isEqualTo(40405);

        // 본인 + 다른 User의 X-User-Id를 함께 보내도 본인 알림만 대상이 된다(정상 처리).
        JsonNode ownRead = patchJson(
                "/api/notifications/" + unreadForA.getId() + "/read",
                bearerWithForeignXUserId(userA, userB), "{}"
        );
        assertThat(status(ownRead)).isEqualTo(200);

        JsonNode unreadCountAfter = getJson("/api/notifications/unread-count", bearer(userA));
        assertThat(unreadCountAfter.path("data").path("unreadCount").asLong()).isZero();
    }

    // ==================== 10. Authentication failure 회귀(대표 endpoint 1개) ====================

    @Test
    void required_endpoint의_40101_회귀_대표_케이스() {
        User user = persistUser("authfail");

        JsonNode noToken = getJson("/api/me/penalties", new HttpHeaders());
        assertThat(status(noToken)).isEqualTo(401);
        assertThat(noToken.path("error").path("code").asInt()).isEqualTo(40101);

        HttpHeaders malformed = new HttpHeaders();
        malformed.setBearerAuth("not-a-jwt");
        assertThat(status(getJson("/api/me/penalties", malformed))).isEqualTo(401);

        String refreshToken = jwtTokenProvider.issueRefreshToken(user.getId());
        HttpHeaders refreshAsAccess = new HttpHeaders();
        refreshAsAccess.setBearerAuth(refreshToken);
        JsonNode refreshMisuse = getJson("/api/me/penalties", refreshAsAccess);
        assertThat(status(refreshMisuse)).isEqualTo(401);
        assertThat(refreshMisuse.path("error").path("code").asInt()).isEqualTo(40101);
    }

    // ==================== 11. Auth endpoint permitAll(최소 확인, 전체 Kakao 플로우는 재검증 안 함) ====================

    // #75-4E §11: "Kakao network 전체 플로우를 다시 테스트하지 마세요. 기존 #75-4C/#75-4D
    // 테스트를 재사용합니다" - /api/auth/kakao의 permitAll은 AuthControllerTest(WebMvcTest,
    // KakaoLoginService를 mock)가 이미 검증한다. 여기서 진짜 accessToken 없이 실제 Kakao
    // 네트워크를 호출하면 외부망 가용성에 테스트가 의존하게 되므로, 이 IT은 네트워크 호출이
    // 필요 없는 refresh/logout 두 endpoint의 permitAll만 real HTTP로 재확인한다.
    @Test
    void auth_endpoint_3개는_Access_JWT_없이_접근_가능하다() {
        JsonNode refreshResult = postJson("/api/auth/refresh", new HttpHeaders(), "{\"refreshToken\":\"not-a-jwt\"}");
        assertThat(status(refreshResult)).isEqualTo(401);
        assertThat(refreshResult.path("error").path("code").asInt()).isEqualTo(40103);

        JsonNode logoutResult = postJson("/api/auth/logout", new HttpHeaders(), "{\"refreshToken\":\"not-a-jwt\"}");
        assertThat(status(logoutResult)).isEqualTo(401);
        assertThat(logoutResult.path("error").path("code").asInt()).isEqualTo(40103);
    }
}
