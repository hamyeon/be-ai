package com.vintic.backend.loadtest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.product.domain.Product;
import com.vintic.backend.product.repository.ProductRepository;
import com.vintic.backend.user.domain.User;
import com.vintic.backend.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 매 k6 run(stage x repetition) 직전에 1번씩 실행한다. 이 클래스의 유일한 책임은 "이번 run
// 전용 hot auction + unrelated auction을 완전히 새로 만든다"는 것이다 - 기존 auction을 재사용해
// currentPrice/Bid가 누적되면, 다음 run의 입찰 금액(seed 기준 startPrice+increment*VU)이 이미
// 지나간 currentPrice보다 낮아져 전부 BID_AMOUNT_TOO_LOW로 거절되거나, 반대로 이전 run의 Bid가
// 이번 run의 PRICE_MISMATCH/WINNER_MISMATCH/success/p95 판정을 오염시킨다 - 그래서 "reset"이
// 아니라 "매번 새 row"를 선택했다: 기존 auction의 currentPrice/currentWinner를 UPDATE로
// 되돌리는 방식은 그 auction을 가리키는 과거 Bid/Idempotency row가 여전히 남아있어 "리셋된
// auction인데 과거 Bid가 딸려있는" 모순 상태를 만들 위험이 있다. 매번 새 auctionId를 쓰면
// HotAuctionInvariantCheck의 조회(auctionId로 필터)가 그 run의 데이터만 자동으로 보게 된다 -
// 별도 정리(TRUNCATE 등)도 필요 없다.
//
// HotAuctionUserSeeder가 미리 만들어 둔 seller/bidder 풀(hot-auction-users.json)을 그대로
// 재사용한다 - User는 상태가 없어 run마다 새로 만들 필요가 없다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
class HotAuctionRoundSeeder {

    private static final Path USERS_PATH = Path.of("..", "loadtest", "k6", "data", "hot-auction-users.json");
    private static final Path OUTPUT_PATH = Path.of("..", "loadtest", "k6", "data", "hot-auction-seed.json");

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private AuctionRepository auctionRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // -DstartPrice=100000 -DbidIncrement=5000 (unrelated auction은 항상 고정값 50000/3000 -
    // hot auction 워크로드와 무관하게 stage마다 "같은 초기 조건"으로 비교돼야 하므로 시스템
    // 프로퍼티로 바꾸지 않는다).
    @Test
    void 이번_run_전용_hot_auction과_unrelated_auction을_새로_만든다() throws IOException {
        if (!Files.exists(USERS_PATH)) {
            throw new IllegalStateException(
                    "hot-auction-users.json이 없습니다. 먼저 HotAuctionUserSeeder를 1회 실행하세요: "
                            + USERS_PATH.toAbsolutePath()
            );
        }
        JsonNode usersJson = objectMapper.readTree(Files.readString(USERS_PATH));
        long sellerId = usersJson.get("sellerId").asLong();
        User seller = userRepository.findById(sellerId).orElseThrow(() -> new IllegalStateException(
                "hot-auction-users.json의 sellerId=" + sellerId + "가 DB에 없습니다 - 다른 DB를 보고 있지 않은지 확인하세요."
        ));
        List<Long> bidderIds = objectMapper.convertValue(
                usersJson.get("bidderIds"),
                objectMapper.getTypeFactory().constructCollectionType(List.class, Long.class)
        );

        long startPrice = Long.getLong("startPrice", 100000L);
        long bidIncrement = Long.getLong("bidIncrement", 5000L);

        Auction hotAuction = persistLiveAuction(seller, startPrice, bidIncrement, "Hot Auction Round");
        // unrelated auction은 hot auction 워크로드와 무관하게 매 run마다 "같은" 초기 조건이어야
        // stage 간 unrelated GET latency 비교가 유효하다 - 고정값을 쓴다(시스템 프로퍼티로 바꾸지 않음).
        Auction unrelatedAuction = persistLiveAuction(seller, 50000L, 3000L, "Unrelated Read Target Round");

        Map<String, Object> hotAuctionJson = new LinkedHashMap<>();
        hotAuctionJson.put("auctionId", hotAuction.getId());
        hotAuctionJson.put("sellerId", seller.getId());
        hotAuctionJson.put("startPrice", startPrice);
        hotAuctionJson.put("bidIncrement", bidIncrement);
        hotAuctionJson.put("bidderIds", bidderIds);

        Map<String, Object> unrelatedAuctionJson = new LinkedHashMap<>();
        unrelatedAuctionJson.put("auctionId", unrelatedAuction.getId());
        unrelatedAuctionJson.put("sellerId", seller.getId());

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("baseUrl", usersJson.get("baseUrl").asText("http://localhost:8080"));
        root.put("hotAuction", hotAuctionJson);
        root.put("unrelatedAuction", unrelatedAuctionJson);

        Files.createDirectories(OUTPUT_PATH.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(OUTPUT_PATH.toFile(), root);

        System.out.println("[hot-auction-round] written to " + OUTPUT_PATH.toAbsolutePath());
        System.out.println("[hot-auction-round] hotAuctionId=" + hotAuction.getId()
                + " unrelatedAuctionId=" + unrelatedAuction.getId()
                + " startPrice=" + startPrice + " bidIncrement=" + bidIncrement
                + " bidderPoolSize=" + bidderIds.size());
    }

    // ManualBidConcurrencyRaceIT.persistLiveAuction()과 동일한 패턴이다(#34/#35 harness).
    private Auction persistLiveAuction(User seller, long startPrice, long bidIncrement, String model) {
        Product product = productRepository.save(new Product(
                seller,
                List.of("https://example.com/hot-auction-load.jpg"),
                "Nike", model, "Panda", 270, "B", "PARTIAL",
                300000, 350000, "285,000~315,000", 290000, "hot-auction-load", "hot-auction-load 부하테스트용 seed 데이터"
        ));
        Auction auction = Auction.schedule(
                product, startPrice, bidIncrement,
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(6)
        );
        auction.start();
        return auctionRepository.save(auction);
    }
}
