package com.vintic.backend.autobid.service;

import com.vintic.backend.ai.purchase.dto.GoalCondition;
import com.vintic.backend.auction.audit.AuctionPriceAudit;
import com.vintic.backend.auction.audit.AuctionPriceAuditRecorder;
import com.vintic.backend.auction.audit.AuctionPriceAuditRepository;
import com.vintic.backend.auction.audit.PriceAuditRule;
import com.vintic.backend.auction.audit.PriceAuditTrigger;
import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.autobid.domain.AutoBidSetting;
import com.vintic.backend.autobid.domain.AutoBidSettingStatus;
import com.vintic.backend.autobid.dto.AutoBidRegisterResponse;
import com.vintic.backend.autobid.dto.AutoBidUpdateResponse;
import com.vintic.backend.autobid.proxy.ProxyPriceEngine;
import com.vintic.backend.autobid.repository.AutoBidSettingRepository;
import com.vintic.backend.bid.domain.BidType;
import com.vintic.backend.bid.repository.BidRepository;
import com.vintic.backend.common.exception.AgentManagedAuctionException;
import com.vintic.backend.common.exception.AuctionClosedException;
import com.vintic.backend.common.exception.AuctionNotFoundException;
import com.vintic.backend.common.exception.AutoBidAlreadyExistsException;
import com.vintic.backend.common.exception.AutoBidNotFoundException;
import com.vintic.backend.common.exception.CapNotIncreasedException;
import com.vintic.backend.common.exception.CapTooLowException;
import com.vintic.backend.common.exception.PenaltyRestrictedException;
import com.vintic.backend.common.exception.SellerCannotBidException;
import com.vintic.backend.config.ClockConfig;
import com.vintic.backend.product.domain.Product;
import com.vintic.backend.purchasegoal.domain.PurchaseGoal;
import com.vintic.backend.purchasegoal.domain.PurchaseGoalStatus;
import com.vintic.backend.purchasegoal.repository.PurchaseGoalRepository;
import com.vintic.backend.purchasegoal.service.AgentManagedAuctionGuard;
import com.vintic.backend.support.TestClockConfig;
import com.vintic.backend.user.domain.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// @DataJpaTest 슬라이스에 서비스를 직접 Import해, 실제 저장/갱신 결과를 DB 재조회로 검증한다.
// (BidCommandServiceTest와 동일한 관례). ProxyPriceEngine/Clock은 신규 의존성이라 명시 Import.
@DataJpaTest
@Import({
        AutoBidCommandService.class, ProxyPriceEngine.class, AuctionPriceAuditRecorder.class,
        AgentManagedAuctionGuard.class, TestClockConfig.class
})
class AutoBidCommandServiceTest {

    @Autowired
    private AutoBidCommandService autoBidCommandService;

    @Autowired
    private AuctionRepository auctionRepository;

    @Autowired
    private AutoBidSettingRepository autoBidSettingRepository;

    @Autowired
    private AuctionPriceAuditRepository auctionPriceAuditRepository;

    @Autowired
    private BidRepository bidRepository;

    @Autowired
    private PurchaseGoalRepository purchaseGoalRepository;

    @Autowired
    private EntityManager entityManager;

    private User persistUser(String email) {
        User user = User.register(email, email, null);
        entityManager.persist(user);
        return user;
    }

    // Day2-B: Day 5(Agent 실제 참여) 이전이라 ACTIVE->ENGAGED 전이 API가 없다 - 테스트
    // 픽스처로 status/currentAuctionId를 직접 맞춘다(PurchaseGoalCommandServiceTest와 동일 관례).
    private PurchaseGoal persistGoal(User owner, PurchaseGoalStatus status, Long currentAuctionId) {
        LocalDateTime now = LocalDateTime.now();
        PurchaseGoal goal = PurchaseGoal.create(
                owner, "New Balance", "nb990", "뉴발란스 990",
                GoalCondition.A, 270, 150000L, null, now.plusDays(7), now
        );
        ReflectionTestUtils.setField(goal, "status", status);
        ReflectionTestUtils.setField(goal, "currentAuctionId", currentAuctionId);
        return purchaseGoalRepository.saveAndFlush(goal);
    }

    private Product persistProduct(User seller) {
        Product product = new Product(
                seller,
                List.of("https://example.com/a.jpg"),
                "Nike", "Dunk Low", "Panda", 270, "B", "PARTIAL",
                300000, 350000, "285,000원 ~ 315,000원", 290000, "사유", "설명"
        );
        entityManager.persist(product);
        return product;
    }

    private Auction persistScheduledAuction(Product product) {
        Auction auction = Auction.schedule(
                product, 10000L, 5000L, LocalDateTime.now().plusHours(1), LocalDateTime.now().plusHours(2)
        );
        entityManager.persist(auction);
        return auction;
    }

    private Auction persistLiveAuction(Product product) {
        Auction auction = Auction.schedule(
                product, 105000L, 5000L, LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(1)
        );
        auction.start();
        entityManager.persist(auction);
        return auction;
    }

    private LocalDateTime fixedNow() {
        return LocalDateTime.ofInstant(TestClockConfig.FIXED_INSTANT, ClockConfig.APP_ZONE);
    }

    private Auction persistLiveAuctionEndingAt(Product product, Long startPrice, LocalDateTime endAt) {
        Auction auction = Auction.schedule(product, startPrice, 5000L, endAt.minusHours(1), endAt);
        auction.start();
        entityManager.persist(auction);
        return auction;
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    // ===== POST =====

    @Test
    void SCHEDULED_경매에_등록하면_RESERVED가_된다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistScheduledAuction(product);
        flushAndClear();

        AutoBidRegisterResponse response = autoBidCommandService.createAutoBid(auction.getId(), bidder.getId(), 20000L);

        assertThat(response.status()).isEqualTo(AutoBidSettingStatus.RESERVED);
        assertThat(response.bidOccurred()).isFalse();
        assertThat(response.resultingBidAmount()).isNull();
        assertThat(response.isHighestBidder()).isFalse();
    }

    // #Day8 결함 수정 이후: 경쟁자가 전혀 없어도(currentWinner가 없는 LIVE 경매에 첫 entrant)
    // §0.13 "예약자 1명도 최소 한 단계는 응찰한다"가 적용돼 즉시 currentWinner가 된다 - 이전에는
    // "경쟁 상대가 없다"는 이유로 winner를 영원히 null로 남겨뒀다(ProxyPriceEngine의 AUTO 트리거가
    // NONE과 달리 이 경우 phantom을 만들지 않던 결함, 수정 완료). maxAmount(200000)로 바로
    // 점프하지 않고 시작가+한 단계(110000)에서 이긴다.
    @Test
    void LIVE_경매에_등록하면_ACTIVE가_되고_유일한_입찰자도_즉시_낙찰된다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        flushAndClear();

        AutoBidRegisterResponse response = autoBidCommandService.createAutoBid(auction.getId(), bidder.getId(), 200000L);

        assertThat(response.status()).isEqualTo(AutoBidSettingStatus.ACTIVE);
        assertThat(response.bidOccurred()).isTrue();
        assertThat(response.resultingBidAmount()).isEqualTo(110000L);
        assertThat(response.isHighestBidder()).isTrue();
        assertThat(response.currentPrice()).isEqualTo(110000L);
        assertThat(response.minNextBidAmount()).isEqualTo(115000L);
        assertThat(response.minCapAmount()).isEqualTo(115000L);

        Auction reloaded = auctionRepository.findById(auction.getId()).orElseThrow();
        assertThat(reloaded.getCurrentWinner().getId()).isEqualTo(bidder.getId());
        assertThat(reloaded.getCurrentPrice()).isEqualTo(110000L);
        assertThat(bidRepository.countByAuctionId(auction.getId())).isEqualTo(1);
    }

    @Test
    void cap이_minCapAmount와_같으면_성공한다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        flushAndClear();

        AutoBidRegisterResponse response = autoBidCommandService.createAutoBid(auction.getId(), bidder.getId(), 110000L);

        assertThat(response.maxAmount()).isEqualTo(110000L);
    }

    @Test
    void cap이_minCapAmount보다_크면_성공한다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        flushAndClear();

        AutoBidRegisterResponse response = autoBidCommandService.createAutoBid(auction.getId(), bidder.getId(), 200000L);

        assertThat(response.maxAmount()).isEqualTo(200000L);
    }

    @Test
    void cap이_minCapAmount_미만이면_40906에_해당하는_예외가_발생한다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        flushAndClear();

        assertThatThrownBy(() -> autoBidCommandService.createAutoBid(auction.getId(), bidder.getId(), 109999L))
                .isInstanceOf(CapTooLowException.class);
    }

    @Test
    void bidIncrement_배수가_아닌_cap도_등록에_성공한다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        flushAndClear();

        // currentPrice=105000, bidIncrement=5000 → minCapAmount=110000. 121000은 5000의 배수가 아니다.
        AutoBidRegisterResponse response = autoBidCommandService.createAutoBid(auction.getId(), bidder.getId(), 121000L);

        assertThat(response.maxAmount()).isEqualTo(121000L);
    }

    @Test
    void 기존_RESERVED_설정이_있으면_40908에_해당하는_예외가_발생한다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistScheduledAuction(product);
        autoBidSettingRepository.saveAndFlush(AutoBidSetting.reserve(auction, bidder, 100000L));

        assertThatThrownBy(() -> autoBidCommandService.createAutoBid(auction.getId(), bidder.getId(), 150000L))
                .isInstanceOf(AutoBidAlreadyExistsException.class);
    }

    @Test
    void 기존_ACTIVE_설정이_있으면_40908에_해당하는_예외가_발생한다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        AutoBidSetting existing = AutoBidSetting.reserve(auction, bidder, 150000L);
        existing.activate();
        autoBidSettingRepository.saveAndFlush(existing);

        assertThatThrownBy(() -> autoBidCommandService.createAutoBid(auction.getId(), bidder.getId(), 200000L))
                .isInstanceOf(AutoBidAlreadyExistsException.class);
    }

    @Test
    void 기존_CAP_REACHED_설정이_있으면_40908에_해당하는_예외가_발생한다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        AutoBidSetting existing = AutoBidSetting.reserve(auction, bidder, 150000L);
        existing.activate();
        existing.markCapReached();
        autoBidSettingRepository.saveAndFlush(existing);

        assertThatThrownBy(() -> autoBidCommandService.createAutoBid(auction.getId(), bidder.getId(), 200000L))
                .isInstanceOf(AutoBidAlreadyExistsException.class);
    }

    @Test
    void CANCELED_이력만_있으면_새로_등록할_수_있다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistScheduledAuction(product);
        AutoBidSetting canceled = AutoBidSetting.reserve(auction, bidder, 100000L);
        canceled.cancel();
        autoBidSettingRepository.saveAndFlush(canceled);
        flushAndClear();

        AutoBidRegisterResponse response = autoBidCommandService.createAutoBid(auction.getId(), bidder.getId(), 130000L);

        assertThat(response.maxAmount()).isEqualTo(130000L);
        assertThat(response.status()).isEqualTo(AutoBidSettingStatus.RESERVED);
        assertThat(autoBidSettingRepository.findAll()).hasSize(2);
    }

    @Test
    void 판매자_본인은_등록할_수_없다() {
        User seller = persistUser("seller@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistScheduledAuction(product);
        flushAndClear();

        assertThatThrownBy(() -> autoBidCommandService.createAutoBid(auction.getId(), seller.getId(), 100000L))
                .isInstanceOf(SellerCannotBidException.class);
    }

    @Test
    void 제재중인_사용자는_등록할_수_없다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        // 서비스는 TestClockConfig의 고정 시각을 기준으로 판정하므로 절대 시각을 쓴다.
        ReflectionTestUtils.setField(bidder, "bidRestrictedUntil", LocalDateTime.of(2099, 1, 1, 0, 0));
        Product product = persistProduct(seller);
        Auction auction = persistScheduledAuction(product);
        flushAndClear();

        assertThatThrownBy(() -> autoBidCommandService.createAutoBid(auction.getId(), bidder.getId(), 100000L))
                .isInstanceOf(PenaltyRestrictedException.class);
    }

    @Test
    void 종료된_경매에는_등록할_수_없다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        auction.end();
        flushAndClear();

        assertThatThrownBy(() -> autoBidCommandService.createAutoBid(auction.getId(), bidder.getId(), 200000L))
                .isInstanceOf(AuctionClosedException.class);
    }

    // ===== PATCH =====

    @Test
    void RESERVED에서_상향_수정은_성공한다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistScheduledAuction(product);
        autoBidSettingRepository.saveAndFlush(AutoBidSetting.reserve(auction, bidder, 100000L));
        flushAndClear();

        AutoBidUpdateResponse response = autoBidCommandService.updateAutoBid(auction.getId(), bidder.getId(), 150000L);

        assertThat(response.maxAmount()).isEqualTo(150000L);
        assertThat(response.status()).isEqualTo(AutoBidSettingStatus.RESERVED);
    }

    @Test
    void RESERVED에서_하향_수정은_성공한다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistScheduledAuction(product);
        autoBidSettingRepository.saveAndFlush(AutoBidSetting.reserve(auction, bidder, 100000L));
        flushAndClear();

        AutoBidUpdateResponse response = autoBidCommandService.updateAutoBid(auction.getId(), bidder.getId(), 20000L);

        assertThat(response.maxAmount()).isEqualTo(20000L);
    }

    @Test
    void RESERVED에서_동일값_수정은_성공한다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistScheduledAuction(product);
        autoBidSettingRepository.saveAndFlush(AutoBidSetting.reserve(auction, bidder, 100000L));
        flushAndClear();

        AutoBidUpdateResponse response = autoBidCommandService.updateAutoBid(auction.getId(), bidder.getId(), 100000L);

        assertThat(response.maxAmount()).isEqualTo(100000L);
    }

    @Test
    void RESERVED에서_minCap_미만으로_수정하면_40906에_해당하는_예외가_발생한다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Product product = persistProduct(seller);
        // startPrice=10000, bidIncrement=5000 → minCapAmount=15000
        Auction auction = persistScheduledAuction(product);
        autoBidSettingRepository.saveAndFlush(AutoBidSetting.reserve(auction, bidder, 100000L));
        flushAndClear();

        assertThatThrownBy(() -> autoBidCommandService.updateAutoBid(auction.getId(), bidder.getId(), 10000L))
                .isInstanceOf(CapTooLowException.class);
    }

    @Test
    void ACTIVE에서_상향_수정은_성공한다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        AutoBidSetting setting = AutoBidSetting.reserve(auction, bidder, 150000L);
        setting.activate();
        autoBidSettingRepository.saveAndFlush(setting);
        flushAndClear();

        AutoBidUpdateResponse response = autoBidCommandService.updateAutoBid(auction.getId(), bidder.getId(), 200000L);

        assertThat(response.maxAmount()).isEqualTo(200000L);
        assertThat(response.status()).isEqualTo(AutoBidSettingStatus.ACTIVE);
    }

    @Test
    void ACTIVE에서_동일값_수정은_40907에_해당하는_예외가_발생한다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        AutoBidSetting setting = AutoBidSetting.reserve(auction, bidder, 150000L);
        setting.activate();
        autoBidSettingRepository.saveAndFlush(setting);
        flushAndClear();

        assertThatThrownBy(() -> autoBidCommandService.updateAutoBid(auction.getId(), bidder.getId(), 150000L))
                .isInstanceOf(CapNotIncreasedException.class);
    }

    @Test
    void ACTIVE에서_하향_수정은_40907에_해당하는_예외가_발생한다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        AutoBidSetting setting = AutoBidSetting.reserve(auction, bidder, 150000L);
        setting.activate();
        autoBidSettingRepository.saveAndFlush(setting);
        flushAndClear();

        assertThatThrownBy(() -> autoBidCommandService.updateAutoBid(auction.getId(), bidder.getId(), 120000L))
                .isInstanceOf(CapNotIncreasedException.class);
    }

    @Test
    void ACTIVE에서_상향이면서_동시에_minCap_미만이면_40906이_우선한다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        // currentPrice=105000, bidIncrement=5000 → minCapAmount=110000
        AutoBidSetting setting = AutoBidSetting.reserve(auction, bidder, 100000L);
        setting.activate();
        autoBidSettingRepository.saveAndFlush(setting);
        flushAndClear();

        // 105000은 oldMaxAmount(100000)보다 크지만(상향) minCapAmount(110000) 미만이다.
        assertThatThrownBy(() -> autoBidCommandService.updateAutoBid(auction.getId(), bidder.getId(), 105000L))
                .isInstanceOf(CapTooLowException.class);
    }

    @Test
    void CAP_REACHED에서_상향해도_여전히_경쟁자에게_못미치면_CAP_REACHED로_유지된다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        User strongerBidder = persistUser("stronger@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        AutoBidSetting competitor = AutoBidSetting.reserve(auction, strongerBidder, 500000L);
        competitor.activate();
        autoBidSettingRepository.saveAndFlush(competitor);
        AutoBidSetting setting = AutoBidSetting.reserve(auction, bidder, 150000L);
        setting.activate();
        setting.markCapReached();
        autoBidSettingRepository.saveAndFlush(setting);
        flushAndClear();

        // 상향해도(200000) 경쟁자(500000)에는 여전히 못 미친다 - Proxy resolution 결과 실제로
        // 이기지 못하므로 CAP_REACHED를 유지해야 한다(§13 policy).
        AutoBidUpdateResponse response = autoBidCommandService.updateAutoBid(auction.getId(), bidder.getId(), 200000L);

        assertThat(response.maxAmount()).isEqualTo(200000L);
        assertThat(response.status()).isEqualTo(AutoBidSettingStatus.CAP_REACHED);
        assertThat(response.bidOccurred()).isFalse();
    }

    @Test
    void CAP_REACHED에서_상향해서_경쟁자를_이기면_ACTIVE로_복귀한다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        User weakerBidder = persistUser("weaker@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        AutoBidSetting competitor = AutoBidSetting.reserve(auction, weakerBidder, 120000L);
        competitor.activate();
        autoBidSettingRepository.saveAndFlush(competitor);
        AutoBidSetting setting = AutoBidSetting.reserve(auction, bidder, 110000L);
        setting.activate();
        setting.markCapReached();
        autoBidSettingRepository.saveAndFlush(setting);
        flushAndClear();

        // 상향(200000)이 경쟁자(120000)를 넘어서면 실제로 이겨서 ACTIVE로 복귀해야 한다.
        AutoBidUpdateResponse response = autoBidCommandService.updateAutoBid(auction.getId(), bidder.getId(), 200000L);

        assertThat(response.status()).isEqualTo(AutoBidSettingStatus.ACTIVE);
        assertThat(response.bidOccurred()).isTrue();
        assertThat(response.isHighestBidder()).isTrue();

        AutoBidSetting reloadedCompetitor = autoBidSettingRepository.findById(competitor.getId()).orElseThrow();
        assertThat(reloadedCompetitor.getStatus()).isEqualTo(AutoBidSettingStatus.CAP_REACHED);
    }

    @Test
    void CAP_REACHED에서_동일값_수정은_40907에_해당하는_예외가_발생한다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        AutoBidSetting setting = AutoBidSetting.reserve(auction, bidder, 150000L);
        setting.activate();
        setting.markCapReached();
        autoBidSettingRepository.saveAndFlush(setting);
        flushAndClear();

        assertThatThrownBy(() -> autoBidCommandService.updateAutoBid(auction.getId(), bidder.getId(), 150000L))
                .isInstanceOf(CapNotIncreasedException.class);
    }

    @Test
    void CAP_REACHED에서_하향_수정은_40907에_해당하는_예외가_발생한다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        AutoBidSetting setting = AutoBidSetting.reserve(auction, bidder, 150000L);
        setting.activate();
        setting.markCapReached();
        autoBidSettingRepository.saveAndFlush(setting);
        flushAndClear();

        assertThatThrownBy(() -> autoBidCommandService.updateAutoBid(auction.getId(), bidder.getId(), 120000L))
                .isInstanceOf(CapNotIncreasedException.class);
    }

    @Test
    void 존재하지_않는_경매를_수정하면_40401에_해당하는_예외가_발생한다() {
        // #46 follow-up: Auction FOR UPDATE를 own-setting 조회보다 먼저 획득하도록 순서를
        // 바꾼 뒤로, 존재하지 않는 auctionId는 (설정 유무와 무관하게) AuctionNotFoundException이
        // 먼저 던져진다 - CREATE/DELETE가 이미 하던 것과 일관된 순서다.
        assertThatThrownBy(() -> autoBidCommandService.updateAutoBid(999L, 1L, 100000L))
                .isInstanceOf(AuctionNotFoundException.class);
    }

    @Test
    void 경매는_존재하지만_현재_설정이_없으면_수정시_40404에_해당하는_예외가_발생한다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistScheduledAuction(product);
        flushAndClear();

        assertThatThrownBy(() -> autoBidCommandService.updateAutoBid(auction.getId(), bidder.getId(), 100000L))
                .isInstanceOf(AutoBidNotFoundException.class);
    }

    @Test
    void Agent가_관리중인_AutoBid_수정은_AgentManagedAuctionException을_던진다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistScheduledAuction(product);
        PurchaseGoal engagedGoal = persistGoal(bidder, PurchaseGoalStatus.ENGAGED, auction.getId());
        autoBidSettingRepository.saveAndFlush(AutoBidSetting.reserve(auction, bidder, 100000L, engagedGoal.getId()));
        flushAndClear();

        assertThatThrownBy(() -> autoBidCommandService.updateAutoBid(auction.getId(), bidder.getId(), 150000L))
                .isInstanceOf(AgentManagedAuctionException.class);
    }

    @Test
    void Goal이_FULFILLED로_종료된_후에는_수정_차단이_풀린다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistScheduledAuction(product);
        PurchaseGoal finishedGoal = persistGoal(bidder, PurchaseGoalStatus.FULFILLED, auction.getId());
        autoBidSettingRepository.saveAndFlush(AutoBidSetting.reserve(auction, bidder, 100000L, finishedGoal.getId()));
        flushAndClear();

        AutoBidUpdateResponse response = autoBidCommandService.updateAutoBid(auction.getId(), bidder.getId(), 150000L);

        assertThat(response.maxAmount()).isEqualTo(150000L);
    }

    // ===== DELETE =====

    @Test
    void RESERVED를_취소하면_CANCELED가_되고_canceledAt이_기록된다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistScheduledAuction(product);
        autoBidSettingRepository.saveAndFlush(AutoBidSetting.reserve(auction, bidder, 100000L));
        flushAndClear();

        var response = autoBidCommandService.cancelAutoBid(auction.getId(), bidder.getId());

        assertThat(response.status()).isEqualTo(AutoBidSettingStatus.CANCELED);
        assertThat(response.canceledAt()).isNotNull();
    }

    @Test
    void ACTIVE를_취소하면_CANCELED가_된다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        AutoBidSetting setting = AutoBidSetting.reserve(auction, bidder, 150000L);
        setting.activate();
        autoBidSettingRepository.saveAndFlush(setting);
        flushAndClear();

        var response = autoBidCommandService.cancelAutoBid(auction.getId(), bidder.getId());

        assertThat(response.status()).isEqualTo(AutoBidSettingStatus.CANCELED);
    }

    @Test
    void CAP_REACHED를_취소하면_CANCELED가_된다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        AutoBidSetting setting = AutoBidSetting.reserve(auction, bidder, 150000L);
        setting.activate();
        setting.markCapReached();
        autoBidSettingRepository.saveAndFlush(setting);
        flushAndClear();

        var response = autoBidCommandService.cancelAutoBid(auction.getId(), bidder.getId());

        assertThat(response.status()).isEqualTo(AutoBidSettingStatus.CANCELED);
    }

    @Test
    void 현재_설정이_없으면_취소시_40404에_해당하는_예외가_발생한다() {
        assertThatThrownBy(() -> autoBidCommandService.cancelAutoBid(999L, 1L))
                .isInstanceOf(AutoBidNotFoundException.class);
    }

    @Test
    void Agent가_관리중인_AutoBid_취소는_AgentManagedAuctionException을_던진다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        PurchaseGoal engagedGoal = persistGoal(bidder, PurchaseGoalStatus.CANCEL_REQUESTED, auction.getId());
        AutoBidSetting setting = AutoBidSetting.reserve(auction, bidder, 150000L, engagedGoal.getId());
        setting.activate();
        autoBidSettingRepository.saveAndFlush(setting);
        flushAndClear();

        assertThatThrownBy(() -> autoBidCommandService.cancelAutoBid(auction.getId(), bidder.getId()))
                .isInstanceOf(AgentManagedAuctionException.class);
    }

    // ===== CANCELED AutoBid는 가격 계산에서 제외 =====

    // CANCELED는 findByAuctionIdAndStatusAndUserIdNot(..., ACTIVE, ...) 조회 조건(status=ACTIVE)
    // 자체에서 걸러져 ProxyResolutionInput.candidates()에 아예 들어가지 않는다 - 확인 결과 엔진에
    // CANCELED가 새는 경로는 없었다(ProxyPriceEngineTest의 "CANCELED는 engine 입력에 아예
    // 존재하지 않는다" 테스트로도 별도 확인됨). 그래서 이 시나리오는 실질적으로 "실제 경쟁자 0명,
    // entrant 혼자"와 같고, #Day8 수정 이후 entrant가 즉시 낙찰된다 - CANCELED가 후보에 새는
    // 회귀라면 엔진을 고쳐야 했겠지만 그런 회귀는 없었으므로 기대값만 새 정책에 맞춘다.
    @Test
    void CANCELED_설정은_상한가가_높아도_경쟁에서_제외되고_새_entrant가_경쟁없이_낙찰된다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        User canceledBidder = persistUser("canceled@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product); // currentPrice=105000, bidIncrement=5000
        // cap이 500000이라 CANCELED가 아니었다면 확실히 entrant를 이겼을 상황을 일부러 만든다.
        AutoBidSetting canceled = AutoBidSetting.reserve(auction, canceledBidder, 500000L);
        canceled.activate();
        canceled.cancel();
        autoBidSettingRepository.saveAndFlush(canceled);
        flushAndClear();

        AutoBidRegisterResponse response = autoBidCommandService.createAutoBid(auction.getId(), bidder.getId(), 200000L);

        assertThat(response.status()).isEqualTo(AutoBidSettingStatus.ACTIVE);
        assertThat(response.bidOccurred()).isTrue(); // CANCELED는 경쟁자가 아니므로 entrant 혼자 응찰
        assertThat(response.isHighestBidder()).isTrue();
        assertThat(response.resultingBidAmount()).isEqualTo(110000L);
        assertThat(response.currentPrice()).isEqualTo(110000L); // 500000이 아니라 105000+5000

        Auction reloaded = auctionRepository.findById(auction.getId()).orElseThrow();
        assertThat(reloaded.getCurrentWinner().getId()).isEqualTo(bidder.getId());
        assertThat(bidRepository.countByAuctionId(auction.getId())).isEqualTo(1); // CANCELED 쪽 Bid는 없다

        List<AuctionPriceAudit> audits = auctionPriceAuditRepository.findByAuctionIdOrderByCreatedAtAsc(auction.getId());
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).getAppliedRule()).isEqualTo(PriceAuditRule.AUTO_ENTRANT_WINS);
        assertThat(audits.get(0).getResultingWinner().getId()).isEqualTo(bidder.getId());
    }

    // ===== 종료 연장 =====

    @Test
    void POST_등록으로_bidOccurred가_true이면_종료_1분_이내에서_연장된다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        User competitorUser = persistUser("competitor@vintic.local");
        Product product = persistProduct(seller);
        LocalDateTime endAt = fixedNow().plusSeconds(30);
        Auction auction = persistLiveAuctionEndingAt(product, 105000L, endAt);
        AutoBidSetting existingCompetitor = AutoBidSetting.reserve(auction, competitorUser, 110000L);
        existingCompetitor.activate();
        autoBidSettingRepository.saveAndFlush(existingCompetitor);
        flushAndClear();

        // entrant(200000)가 기존 경쟁자(110000)를 실제로 이겨 자신의 AUTO Bid가 저장된다(bidOccurred=true).
        AutoBidRegisterResponse response = autoBidCommandService.createAutoBid(auction.getId(), bidder.getId(), 200000L);

        assertThat(response.bidOccurred()).isTrue();
        Auction reloaded = auctionRepository.findById(auction.getId()).orElseThrow();
        assertThat(reloaded.getExtensionCount()).isEqualTo(1);
        assertThat(reloaded.getEndAt()).isEqualTo(endAt.plusMinutes(3));
    }

    // #Day8: "경쟁자 없는 POST 등록은 bidOccurred=false"라는 옛 전제가 사라졌다(유일한 entrant도
    // 즉시 낙찰된다) - 그래서 이 최초 등록 자체는 bidOccurred=true가 되어 1회 연장된다(위
    // POST_등록으로_bidOccurred가_true이면... 테스트와 동일한 경로). "bidOccurred=false여도
    // 종료 임박 시 연장되지 않는다"는 원래 취지를 지키려면, 이제는 §0.13상 실제로 bidOccurred=false가
    // 나오는 유일한 경우(이미 currentWinner인 entrant의 cap 인상)로 시나리오를 바꿔야 한다.
    @Test
    void PATCH_상향으로_bidOccurred가_false이면_종료_1분_이내여도_추가_연장되지_않는다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Product product = persistProduct(seller);
        LocalDateTime endAt = fixedNow().plusSeconds(30);
        Auction auction = persistLiveAuctionEndingAt(product, 105000L, endAt);
        flushAndClear();

        // 최초 등록(유일한 입찰자)은 실제로 낙찰되며 종료 1분 이내라 1회 연장된다 - 이 테스트의
        // 관심사는 그 다음 cap 인상이다.
        autoBidCommandService.createAutoBid(auction.getId(), bidder.getId(), 200000L);
        flushAndClear();
        Auction afterFirstBid = auctionRepository.findById(auction.getId()).orElseThrow();
        assertThat(afterFirstBid.getExtensionCount()).isEqualTo(1);
        LocalDateTime endAtAfterFirstExtension = afterFirstBid.getEndAt();

        // 경쟁자가 없어 cap만 올리는 것은 스스로에게 다시 응찰하지 않는다(bidOccurred=false) -
        // 종료 시각이 여전히 1분 이내여도 추가 연장은 없다.
        AutoBidUpdateResponse response = autoBidCommandService.updateAutoBid(auction.getId(), bidder.getId(), 300000L);

        assertThat(response.bidOccurred()).isFalse();
        Auction reloaded = auctionRepository.findById(auction.getId()).orElseThrow();
        assertThat(reloaded.getExtensionCount()).isEqualTo(1);
        assertThat(reloaded.getEndAt()).isEqualTo(endAtAfterFirstExtension);
    }

    @Test
    void PATCH_상향으로_bidOccurred가_true이면_종료_1분_이내에서_연장된다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        User weakerBidder = persistUser("weaker@vintic.local");
        Product product = persistProduct(seller);
        LocalDateTime endAt = fixedNow().plusSeconds(30);
        Auction auction = persistLiveAuctionEndingAt(product, 105000L, endAt);
        AutoBidSetting competitor = AutoBidSetting.reserve(auction, weakerBidder, 120000L);
        competitor.activate();
        autoBidSettingRepository.saveAndFlush(competitor);
        AutoBidSetting setting = AutoBidSetting.reserve(auction, bidder, 110000L);
        setting.activate();
        setting.markCapReached();
        autoBidSettingRepository.saveAndFlush(setting);
        flushAndClear();

        AutoBidUpdateResponse response = autoBidCommandService.updateAutoBid(auction.getId(), bidder.getId(), 200000L);

        assertThat(response.bidOccurred()).isTrue();
        Auction reloaded = auctionRepository.findById(auction.getId()).orElseThrow();
        assertThat(reloaded.getExtensionCount()).isEqualTo(1);
        assertThat(reloaded.getEndAt()).isEqualTo(endAt.plusMinutes(3));
    }

    @Test
    void PATCH_상향해도_bidOccurred가_false이면_종료_1분_이내여도_연장되지_않는다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        User strongerBidder = persistUser("stronger@vintic.local");
        Product product = persistProduct(seller);
        LocalDateTime endAt = fixedNow().plusSeconds(30);
        Auction auction = persistLiveAuctionEndingAt(product, 105000L, endAt);
        AutoBidSetting competitor = AutoBidSetting.reserve(auction, strongerBidder, 500000L);
        competitor.activate();
        autoBidSettingRepository.saveAndFlush(competitor);
        AutoBidSetting setting = AutoBidSetting.reserve(auction, bidder, 150000L);
        setting.activate();
        setting.markCapReached();
        autoBidSettingRepository.saveAndFlush(setting);
        flushAndClear();

        // 상향해도(200000) 경쟁자(500000)에는 여전히 못 미쳐 bidOccurred=false다.
        AutoBidUpdateResponse response = autoBidCommandService.updateAutoBid(auction.getId(), bidder.getId(), 200000L);

        assertThat(response.bidOccurred()).isFalse();
        Auction reloaded = auctionRepository.findById(auction.getId()).orElseThrow();
        assertThat(reloaded.getExtensionCount()).isZero();
        assertThat(reloaded.getEndAt()).isEqualTo(endAt);
    }

    @Test
    void 이미_취소된_설정을_다시_취소요청하면_40404에_해당하는_예외가_발생한다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistScheduledAuction(product);
        autoBidSettingRepository.saveAndFlush(AutoBidSetting.reserve(auction, bidder, 100000L));
        flushAndClear();

        autoBidCommandService.cancelAutoBid(auction.getId(), bidder.getId());

        assertThatThrownBy(() -> autoBidCommandService.cancelAutoBid(auction.getId(), bidder.getId()))
                .isInstanceOf(AutoBidNotFoundException.class);
    }

    // ===== Price Audit Log (#45) =====

    @Test
    void POST_등록으로_entrant가_이기면_AUTO_ENTRANT_WINS_audit이_남는다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        User weakerBidder = persistUser("weaker@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product); // currentPrice=105000, bidIncrement=5000
        AutoBidSetting competitor = AutoBidSetting.reserve(auction, weakerBidder, 120000L);
        competitor.activate();
        autoBidSettingRepository.saveAndFlush(competitor);
        flushAndClear();

        autoBidCommandService.createAutoBid(auction.getId(), bidder.getId(), 200000L);

        List<AuctionPriceAudit> audits = auctionPriceAuditRepository.findByAuctionIdOrderByCreatedAtAsc(auction.getId());
        assertThat(audits).hasSize(1);
        AuctionPriceAudit audit = audits.get(0);
        assertThat(audit.getBeforePrice()).isEqualTo(105000L);
        assertThat(audit.getAfterPrice()).isEqualTo(125000L); // min(200000, 120000+5000)
        assertThat(audit.getResultingWinner().getId()).isEqualTo(bidder.getId());
        assertThat(audit.getTrigger()).isEqualTo(PriceAuditTrigger.AUTO_BID_CREATE);
        assertThat(audit.getAppliedRule()).isEqualTo(PriceAuditRule.AUTO_ENTRANT_WINS);
        assertThat(audit.getBidType()).isEqualTo(BidType.AUTO);
    }

    @Test
    void POST_등록으로_entrant가_지면_AUTO_INCUMBENT_DEFENDS_audit이_남는다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        User strongerBidder = persistUser("stronger@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        AutoBidSetting competitor = AutoBidSetting.reserve(auction, strongerBidder, 500000L);
        competitor.activate();
        autoBidSettingRepository.saveAndFlush(competitor);
        flushAndClear();

        autoBidCommandService.createAutoBid(auction.getId(), bidder.getId(), 200000L);

        AuctionPriceAudit audit = auctionPriceAuditRepository.findByAuctionIdOrderByCreatedAtAsc(auction.getId()).get(0);
        assertThat(audit.getResultingWinner().getId()).isEqualTo(strongerBidder.getId());
        assertThat(audit.getAppliedRule()).isEqualTo(PriceAuditRule.AUTO_INCUMBENT_DEFENDS);
        assertThat(audit.getBidType()).isEqualTo(BidType.AUTO);
    }

    @Test
    void PATCH_상향으로_entrant가_이기면_AUTO_ENTRANT_WINS_audit이_남는다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        User weakerBidder = persistUser("weaker@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        AutoBidSetting competitor = AutoBidSetting.reserve(auction, weakerBidder, 120000L);
        competitor.activate();
        autoBidSettingRepository.saveAndFlush(competitor);
        AutoBidSetting setting = AutoBidSetting.reserve(auction, bidder, 110000L);
        setting.activate();
        setting.markCapReached();
        autoBidSettingRepository.saveAndFlush(setting);
        flushAndClear();

        autoBidCommandService.updateAutoBid(auction.getId(), bidder.getId(), 200000L);

        List<AuctionPriceAudit> audits = auctionPriceAuditRepository.findByAuctionIdOrderByCreatedAtAsc(auction.getId());
        assertThat(audits).hasSize(1);
        AuctionPriceAudit audit = audits.get(0);
        assertThat(audit.getResultingWinner().getId()).isEqualTo(bidder.getId());
        assertThat(audit.getTrigger()).isEqualTo(PriceAuditTrigger.AUTO_BID_UPDATE);
        assertThat(audit.getAppliedRule()).isEqualTo(PriceAuditRule.AUTO_ENTRANT_WINS);
    }

    // #Day8: 경쟁자가 없어도 §0.13 "예약자 1명도 최소 한 단계는 응찰"에 따라 entrant가 즉시
    // 낙찰되므로(winner null -> bidder, price 105000 -> 110000) priceChanged/winnerChanged가
    // 모두 참이 돼 audit이 남는다 - "경쟁이 없으면 audit도 없다"는 옛 전제 자체가 이제 성립하지 않는다.
    @Test
    void 경쟁없는_LIVE_등록도_유일한_입찰자가_낙찰되며_AUTO_ENTRANT_WINS_audit을_남긴다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        flushAndClear();

        AutoBidRegisterResponse response = autoBidCommandService.createAutoBid(auction.getId(), bidder.getId(), 200000L);

        assertThat(response.bidOccurred()).isTrue();
        List<AuctionPriceAudit> audits = auctionPriceAuditRepository.findByAuctionIdOrderByCreatedAtAsc(auction.getId());
        assertThat(audits).hasSize(1);
        AuctionPriceAudit audit = audits.get(0);
        assertThat(audit.getResultingWinner().getId()).isEqualTo(bidder.getId());
        assertThat(audit.getTrigger()).isEqualTo(PriceAuditTrigger.AUTO_BID_CREATE);
        assertThat(audit.getAppliedRule()).isEqualTo(PriceAuditRule.AUTO_ENTRANT_WINS);
    }

    // #Day8: 이전에는 AutoBidSetting을 직접 ACTIVE로 만들어(엔진을 거치지 않고) "암묵적
    // currentWinner"를 흉내냈다 - 이제는 그런 상태 자체가 실제 등록 경로로는 나오지 않는다
    // (유일한 entrant는 등록 즉시 진짜 currentWinner가 된다). 그래서 먼저 실제 createAutoBid로
    // bidder를 진짜 currentWinner로 만든 뒤(이 최초 등록 자체는 audit을 1건 남긴다), 그 cap만
    // 올리는 두 번째 호출이 §0.13대로 스스로에게 다시 응찰하지 않아 추가 audit이 없는지를 본다.
    @Test
    void 자기자신이_이미_currentWinner면_cap을_올려도_추가_audit을_남기지_않는다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        flushAndClear();

        autoBidCommandService.createAutoBid(auction.getId(), bidder.getId(), 110000L);
        flushAndClear();
        assertThat(auctionPriceAuditRepository.countByAuctionId(auction.getId())).isEqualTo(1); // 최초 단독 낙찰 audit

        // 경쟁자가 없어 이 entrant는 이미 실제 currentWinner다 - cap만 올려도 스스로에게 다시
        // 응찰하지 않는다(§0.13) - 가격/승자 변화가 없으므로 no-op, 추가 audit 없음.
        AutoBidUpdateResponse response = autoBidCommandService.updateAutoBid(auction.getId(), bidder.getId(), 300000L);

        assertThat(response.bidOccurred()).isFalse();
        assertThat(auctionPriceAuditRepository.countByAuctionId(auction.getId())).isEqualTo(1); // 늘지 않음
    }

    @Test
    void 검증에_실패한_등록은_audit을_남기지_않는다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        flushAndClear();

        assertThatThrownBy(() -> autoBidCommandService.createAutoBid(auction.getId(), bidder.getId(), 109999L))
                .isInstanceOf(CapTooLowException.class);

        assertThat(auctionPriceAuditRepository.countByAuctionId(auction.getId())).isZero();
    }
}
