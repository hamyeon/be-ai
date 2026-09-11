package com.vintic.backend.loadtest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.user.domain.User;
import com.vintic.backend.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// hot-auction k6 부하 테스트용 seller/bidder 계정을 딱 1번 만든다. Auction과 달리 User는 run마다
// 새로 만들 이유가 없다 - 사용자 자체는 상태가 없고(bid 여부와 무관), HotAuctionRoundSeeder가
// 매 run마다 새 Auction만 만들어 이 풀을 재사용한다.
//
// production 코드가 아니라 test 소스에만 존재한다. @Testcontainers를 쓰지 않는다 - 로컬에서
// 이미 떠 있는 실제 Spring Boot 앱(application-local.yml의 실제 MySQL)과 같은 DB에 심어야
// k6가 때리는 서버가 그 사용자를 실제로 본다.
//
// 인증: local 프로필 실제 구조(MockAuthInterceptor + X-User-Id, MockUserRegistry가 users
// 테이블 조회)를 그대로 따른다 - **local baseline 전용이다.** dev/prod/AWS에서는 X-User-Id가
// 인증 수단이 아니다(JwtAuthenticationFilter가 Authorization: Bearer JWT만 신뢰) - README
// "인증 경계" 절 참고.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
class HotAuctionUserSeeder {

    private static final Path OUTPUT_PATH = Path.of("..", "loadtest", "k6", "data", "hot-auction-users.json");

    @Autowired
    private UserRepository userRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // -DbidderCount=200 (기본 200, 8~200 VU 전 stage가 이 풀을 나눠 재사용한다)
    @Test
    void 핫옥션_부하테스트용_seller_bidder_풀을_로컬DB에_1회_생성한다() throws IOException {
        int bidderCount = Integer.getInteger("bidderCount", 200);
        String runTag = UUID.randomUUID().toString().substring(0, 8);

        User seller = userRepository.save(User.register(
                "hot-auction-seller-" + runTag + "@vintic.local", "hot-auction-seller", null
        ));

        List<Long> bidderIds = new ArrayList<>();
        for (int i = 0; i < bidderCount; i++) {
            User bidder = userRepository.save(User.register(
                    "hot-auction-bidder-" + runTag + "-" + i + "@vintic.local", "bidder" + i, null
            ));
            bidderIds.add(bidder.getId());
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("baseUrl", "http://localhost:8080");
        root.put("sellerId", seller.getId());
        root.put("bidderIds", bidderIds);

        Files.createDirectories(OUTPUT_PATH.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(OUTPUT_PATH.toFile(), root);

        System.out.println("[hot-auction-users] written to " + OUTPUT_PATH.toAbsolutePath());
        System.out.println("[hot-auction-users] sellerId=" + seller.getId() + " bidderCount=" + bidderIds.size());
    }
}
