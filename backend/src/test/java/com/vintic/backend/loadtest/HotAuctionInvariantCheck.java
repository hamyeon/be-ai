package com.vintic.backend.loadtest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.bid.domain.Bid;
import com.vintic.backend.bid.repository.BidRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

// hot-auction k6 부하 테스트가 끝난 뒤 실행하는 post-state invariant 검증이다. #34/#35
// concurrency 실험(ManualBidConcurrencyRaceIT)이 확립한 invariant 개념(PRICE_MISMATCH/
// WINNER_MISMATCH/LOST_UPDATE/SUCCESS_COUNT_MISMATCH)을 그대로 재사용한다 - 새 invariant
// 정의를 만들지 않았다. 차이는 그 harness가 in-JVM 직접 호출 결과(WorkerOutcome)와 비교하는
// 반면, 이 클래스는 k6가 실제 HTTP로 만든 DB 최종 상태만 갖고 검증한다는 점이다(k6 프로세스가
// 별도라 성공 카운트를 직접 넘겨받을 수 없다 - 대신 idempotencies 테이블의 COMPLETED 클레임
// 수를 "성공 카운트의 근거"로 쓴다. 실패한 요청은 IdempotencyClaimService가 애초에 커밋하지
// 않으므로 이 테이블에는 성공한 시도만 남는다).
//
// 검사 범위: 이 run 전용 auctionId(HotAuctionRoundSeeder가 매 run마다 새로 만든 hot-auction-
// seed.json의 hotAuction.auctionId)로만 필터한다 - 전체 DB나 이 테이블의 모든 행을 보지 않는다.
// Bid 조회(findByAuctionIdOrderByCreatedAtDescIdDesc)와 idempotencies 조회
// (operation_scope='PLACE_BID:{auctionId}') 둘 다 이 auctionId 하나에만 스코프된다. 과거
// run이나 이 부하 테스트와 무관한 다른 auction/user의 Bid/Idempotency가 이 카운트에 섞여
// 들어올 수 없다 - operation_scope 문자열 자체가 특정 auctionId를 그대로 담고 있어서, 다른
// auction의 idempotency row는 애초에 이 WHERE절과 절대 일치하지 않는다. 이 보장은
// HotAuctionRoundSeeder가 매 run마다 새 auctionId를 발급한다는 전제 위에서만 성립한다 - 같은
// auction을 여러 run이 재사용하면 그 run들의 Bid/idempotency가 전부 이 필터를 통과해 섞인다.
//
// HotAuctionRoundSeeder와 마찬가지로 @Testcontainers를 쓰지 않는다 - k6가 실제로 때린 서버와
// 같은 local DB를 그대로 읽어야 한다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
class HotAuctionInvariantCheck {

    private static final Path SEED_PATH = Path.of("..", "loadtest", "k6", "data", "hot-auction-seed.json");
    // run-stages.sh/smoke-test.sh가 이 파일을 매 run 직후 results/invariant-{tag}.json으로
    // 복사한다 - 콘솔/HTML 리포트를 파싱하지 않고 구조화된 값을 안정적으로 회수하기 위해서다.
    private static final Path RESULT_PATH = Path.of("..", "loadtest", "k6", "data", "hot-auction-invariant-result.json");

    @Autowired
    private AuctionRepository auctionRepository;

    @Autowired
    private BidRepository bidRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void k6_부하테스트_이후_hot_auction_post_state가_invariant를_위반하지_않는다() throws Exception {
        JsonNode seed = objectMapper.readTree(Files.readString(SEED_PATH));
        long auctionId = seed.get("hotAuction").get("auctionId").asLong();

        Auction auction = auctionRepository.findById(auctionId).orElseThrow();
        List<Bid> bids = bidRepository.findByAuctionIdOrderByCreatedAtDescIdDesc(auctionId, Pageable.unpaged())
                .getContent();

        List<String> violations = new ArrayList<>();

        if (!bids.isEmpty()) {
            long actualMaxBidAmount = bids.stream().mapToLong(Bid::getAmount).max().orElseThrow();
            Long actualMaxBidderId = bids.stream()
                    .max((a, b) -> Long.compare(a.getAmount(), b.getAmount()))
                    .map(b -> b.getUser().getId())
                    .orElse(null);

            // 1. Auction의 최종 currentPrice와 최종 winner가 서로 일관적인가
            if (!Objects.equals(auction.getCurrentPrice(), actualMaxBidAmount)) {
                violations.add("PRICE_MISMATCH: currentPrice=" + auction.getCurrentPrice()
                        + " actualMaxBid=" + actualMaxBidAmount);
            }
            Long currentWinnerId = auction.getCurrentWinner() != null ? auction.getCurrentWinner().getId() : null;
            if (!Objects.equals(currentWinnerId, actualMaxBidderId)) {
                violations.add("WINNER_MISMATCH: currentWinner=" + currentWinnerId
                        + " actualMaxBidder=" + actualMaxBidderId);
            }

            // 2. 가격이 역전되거나 더 낮은 입찰이 최종 최고가를 덮어쓴 흔적이 없는가(= 어떤
            // 영속된 Bid도 최종 currentPrice보다 클 수 없다 - lost update)
            boolean lostUpdate = bids.stream().anyMatch(b -> b.getAmount() > auction.getCurrentPrice());
            if (lostUpdate) {
                violations.add("LOST_UPDATE: a Bid amount exceeds Auction.currentPrice");
            }
        }

        // 3. 동일한 요청이 멱등성 문제로 중복 반영되지 않았는가: 성공한 입찰 시도(=커밋된
        // idempotencies row, scope=PLACE_BID:auctionId)마다 정확히 Bid가 1건씩만 생겨야 한다.
        // 이 부하 테스트는 AutoBidSetting을 만들지 않으므로 Proxy가 반격할 후보가 없다 - 성공한
        // manual bid 1건당 정확히 Bid 1건(MANUAL)만 생긴다는 전제가 항상 성립한다.
        Integer completedIdempotencyCount = jdbcTemplate.queryForObject(
                "select count(*) from idempotencies where operation_scope = ?",
                Integer.class, "PLACE_BID:" + auctionId
        );
        if (!Objects.equals(completedIdempotencyCount, bids.size())) {
            violations.add("IDEMPOTENCY_DUPLICATE_OR_MISSING: completedClaims=" + completedIdempotencyCount
                    + " persistedBids=" + bids.size());
        }

        // 4. Auction.endAt 이후 생성된 Bid가 없는가(마감 이후 반영된 입찰은 §BidCommandService의
        // hasReachedDeadline 거절이 뚫렸다는 뜻이다).
        Integer bidsAfterEndAt = jdbcTemplate.queryForObject(
                "select count(*) from bids where auction_id = ? and created_at > " +
                        "(select end_at from auctions where id = ?)",
                Integer.class, auctionId, auctionId
        );
        if (bidsAfterEndAt != null && bidsAfterEndAt > 0) {
            violations.add("BID_AFTER_END_AT: count=" + bidsAfterEndAt);
        }

        // 5. price reversal: id 순서(=커밋 순서)로 봤을 때 어떤 Bid도 그 이전까지의 최고 금액보다
        // 낮으면 안 된다(더 낮은 입찰이 그 시점의 최고가를 "덮어쓴" 흔적). LOST_UPDATE(2번)는
        // 최종 currentPrice 기준 한 번만 보지만, 이 체크는 전체 커밋 시퀀스를 다 훑는다는 점이
        // 다르다 - 중간에 반짝 역전됐다가 다시 정상으로 돌아온 경우도 잡아낸다.
        Integer priceReversalCount = jdbcTemplate.queryForObject("""
                select count(*) from (
                    select b.id, b.amount,
                           max(b.amount) over (order by b.id rows between unbounded preceding and 1 preceding) as prior_max
                    from bids b
                    where b.auction_id = ?
                ) t
                where prior_max is not null and amount < prior_max
                """,
                Integer.class, auctionId
        );
        if (priceReversalCount != null && priceReversalCount > 0) {
            violations.add("PRICE_REVERSAL: count=" + priceReversalCount);
        }

        System.out.println("[hot-auction-invariant] auctionId=" + auctionId
                + " finalCurrentPrice=" + auction.getCurrentPrice()
                + " finalWinnerId=" + (auction.getCurrentWinner() != null ? auction.getCurrentWinner().getId() : null)
                + " persistedBidCount=" + bids.size()
                + " completedIdempotencyClaims=" + completedIdempotencyCount
                + " bidsAfterEndAt=" + bidsAfterEndAt
                + " priceReversalCount=" + priceReversalCount
                + " violations=" + violations);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("auctionId", auctionId);
        result.put("finalCurrentPrice", auction.getCurrentPrice());
        result.put("finalWinnerId", auction.getCurrentWinner() != null ? auction.getCurrentWinner().getId() : null);
        result.put("persistedBidCount", bids.size());
        result.put("completedIdempotencyClaims", completedIdempotencyCount);
        result.put("bidsAfterEndAt", bidsAfterEndAt);
        result.put("priceReversalCount", priceReversalCount);
        result.put("violations", violations);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(RESULT_PATH.toFile(), result);

        assertThat(violations).as("hot-auction post-state invariant violations").isEmpty();
    }
}
