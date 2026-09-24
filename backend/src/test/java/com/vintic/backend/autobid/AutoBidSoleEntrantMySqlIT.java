package com.vintic.backend.autobid;

import com.vintic.backend.ai.purchase.dto.GoalCondition;
import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.auction.service.AuctionEndService;
import com.vintic.backend.autobid.domain.AutoBidSetting;
import com.vintic.backend.autobid.repository.AutoBidSettingRepository;
import com.vintic.backend.autobid.service.AutoBidCommandService;
import com.vintic.backend.bid.repository.BidRepository;
import com.vintic.backend.order.repository.OrderRepository;
import com.vintic.backend.product.domain.Product;
import com.vintic.backend.product.repository.ProductRepository;
import com.vintic.backend.purchasegoal.domain.PurchaseGoal;
import com.vintic.backend.purchasegoal.repository.PurchaseGoalRepository;
import com.vintic.backend.user.domain.User;
import com.vintic.backend.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// Day 8 후속: 기존 입찰(수동/AutoBid 어느 쪽으로도)이 전혀 없는 LIVE 경매에 첫 AutoBid가 "혼자"
// 등록되면, ProxyPriceEngine이 실제로 winner/Bid를 만들지 않고 무기한 대기시켰다(#42
// 리팩토링부터 존재한 기존 결함 - AUTO 트리거는 currentWinner가 있을 때만 phantom을 만들고,
// "예약자 1명도 최소 한 단계는 응찰한다"(§0.13) 규칙은 NONE 트리거(경매 시작 시 RESERVED 일괄
// 정산)에만 구현돼 있었다). ProxyPriceEngine.buildField()를 고쳐 AUTO도 currentWinner가 없을 때
// NONE과 동일한 phantom을 쓰게 했다 - 기존 락(Auction FOR UPDATE)·가격 공식(EffectiveCapCalculator)은
// 그대로 재사용하고 새 트랜잭션이나 경로를 만들지 않았다. 이 테스트는 그 수정이 실제
// AutoBidCommandService 경로(일반/Agent 양쪽) + 실제 MySQL 락을 통해 끝까지 이어지는지 확인한다.
// 경쟁자를 추가해 결과를 우회하지 않는다 - 정말로 "혼자" 등록한 경우만 본다.
@SpringBootTest
@ActiveProfiles("local")
@Testcontainers
class AutoBidSoleEntrantMySqlIT {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("purchase-agent.scan.enabled", () -> "false");
        registry.add("auction.lifecycle.start.enabled", () -> "false");
        registry.add("auction.lifecycle.end.enabled", () -> "false");
        registry.add("payment.expiration.enabled", () -> "false");
        registry.add("backup-offer.expiration.enabled", () -> "false");
    }

    @Autowired
    private AutoBidCommandService autoBidCommandService;

    @Autowired
    private AuctionEndService auctionEndService;

    @Autowired
    private AuctionRepository auctionRepository;

    @Autowired
    private BidRepository bidRepository;

    @Autowired
    private AutoBidSettingRepository autoBidSettingRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PurchaseGoalRepository purchaseGoalRepository;

    private User persistUser(String email) {
        return userRepository.save(User.register(email, email, null));
    }

    private Product persistProduct(User seller) {
        return productRepository.save(new Product(
                seller, List.of("https://example.com/a.jpg"),
                "Nike", "Dunk Low", "Panda", 270, "B", "PARTIAL",
                300000, 350000, "", 290000, "", ""
        ));
    }

    // 기존 입찰(수동이든 AutoBid든)이 전혀 없는, 방금 시작된 LIVE 경매다.
    private Auction persistFreshLiveAuction(Product product) {
        Auction auction = Auction.schedule(product, 100000L, 5000L, LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1));
        auction.start();
        return auctionRepository.save(auction);
    }

    private void endAndSettle(Long auctionId) {
        Auction auction = auctionRepository.findById(auctionId).orElseThrow();
        ReflectionTestUtils.setField(auction, "endAt", LocalDateTime.now().minusSeconds(1));
        auctionRepository.save(auction);
        auctionEndService.endIfDue(auctionId);
    }

    @Test
    void 일반_AutoBid도_유일한_입찰자면_첫_유효_입찰이_생성되고_낙찰된다() {
        User seller = persistUser("sole-seller1@vintic.local");
        User buyer = persistUser("sole-buyer1@vintic.local");
        Auction auction = persistFreshLiveAuction(persistProduct(seller));

        autoBidCommandService.createAutoBid(auction.getId(), buyer.getId(), 200000L);

        // 재현/수정 확인 1: 등록 한 번으로 정확히 하나의 유효 입찰이 만들어졌다(중복 없음).
        assertThat(bidRepository.countByAuctionId(auction.getId())).isEqualTo(1);

        Auction reloaded = auctionRepository.findById(auction.getId()).orElseThrow();
        assertThat(reloaded.getCurrentWinner()).isNotNull();
        assertThat(reloaded.getCurrentWinner().getId()).isEqualTo(buyer.getId());
        // maxAmount(200000)로 바로 점프하지 않고 시작가+한 단계(105000)에서 이긴다.
        assertThat(reloaded.getCurrentPrice()).isEqualTo(105000L);

        AutoBidSetting setting = autoBidSettingRepository.findByAuctionIdAndUserIdAndActiveSlotTrue(auction.getId(), buyer.getId())
                .orElseThrow();
        assertThat(setting.getPurchaseGoalId()).isNull();

        // 재현/수정 확인 2: 이 상태로 종료하면(정산은 기존 AuctionSettlementService 그대로) 낙찰 Order가 있다.
        endAndSettle(auction.getId());
        assertThat(orderRepository.findByAuctionIdAndBuyerId(auction.getId(), buyer.getId())).isPresent();
    }

    @Test
    void Agent_AutoBid도_유일한_입찰자면_첫_유효_입찰이_생성되고_낙찰된다() {
        User seller = persistUser("sole-seller2@vintic.local");
        User buyer = persistUser("sole-buyer2@vintic.local");
        Auction auction = persistFreshLiveAuction(persistProduct(seller));
        PurchaseGoal goal = purchaseGoalRepository.save(PurchaseGoal.create(
                buyer, "Nike", "dunklow", "나이키 덩크 로우",
                GoalCondition.B, null, 300000L, null,
                LocalDateTime.now().plusDays(7), LocalDateTime.now()
        ));

        // Day 5 engage()가 실제로 부르는 5-arg 오버로드(purchaseGoalId 포함)를 그대로 쓴다.
        autoBidCommandService.createAutoBid(auction.getId(), buyer.getId(), 200000L, null, goal.getId());

        assertThat(bidRepository.countByAuctionId(auction.getId())).isEqualTo(1);

        Auction reloaded = auctionRepository.findById(auction.getId()).orElseThrow();
        assertThat(reloaded.getCurrentWinner()).isNotNull();
        assertThat(reloaded.getCurrentWinner().getId()).isEqualTo(buyer.getId());
        assertThat(reloaded.getCurrentPrice()).isEqualTo(105000L);

        AutoBidSetting setting = autoBidSettingRepository.findByAuctionIdAndUserIdAndActiveSlotTrue(auction.getId(), buyer.getId())
                .orElseThrow();
        assertThat(setting.getPurchaseGoalId()).isEqualTo(goal.getId());

        endAndSettle(auction.getId());
        assertThat(orderRepository.findByAuctionIdAndBuyerId(auction.getId(), buyer.getId())).isPresent();
    }
}
