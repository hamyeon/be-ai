package com.vintic.backend.loadtest;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

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

// Day5 4단계: loadtest/k6/hot-auction-bid.js(experiment/hot-auction-load 브랜치, 이 브랜치에는
// 없음)가 쓰던 것과 동일한 seeder를 그대로 옮겨왔다 - 로직/도메인 호출 방식 무변경(git show
// experiment/hot-auction-load:backend/src/test/java/.../HotAuctionRoundSeeder.java와 동일).
// loadtest/k6/data/hot-auction-users.json(기존에 이미 존재, User는 상태가 없어 재사용 가능,
// 실제로 id 4~8이 여전히 유효함을 DB 조회로 확인함)의 seller/bidder 풀을 그대로 쓰고, 이번
// run 전용 hot auction + unrelated auction만 새로 만든다 - 기존 auction을 재사용하면
// currentPrice가 누적돼 있어 startPrice+increment*VU 계산이 어긋난다(README 참고).
//
// production 코드가 아니라 test 소스에만 존재한다. 로컬에 이미 떠 있는 실제 local 프로필 앱과
// 같은 DB에 심어야 k6가 때리는 서버가 그 데이터를 실제로 본다.
@EnabledIfEnvironmentVariable(named = "RUN_HOT_AUCTION_SEEDER", matches = "(?i)true")
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

    @Test
    void 이번_run_전용_hot_auction과_unrelated_auction을_새로_만든다() throws IOException {
        if (!Files.exists(USERS_PATH)) {
            throw new IllegalStateException(
                    "hot-auction-users.json이 없습니다: " + USERS_PATH.toAbsolutePath()
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
