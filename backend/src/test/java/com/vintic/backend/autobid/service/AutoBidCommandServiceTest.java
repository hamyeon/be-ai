package com.vintic.backend.autobid.service;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.autobid.domain.AutoBidSetting;
import com.vintic.backend.autobid.domain.AutoBidSettingStatus;
import com.vintic.backend.autobid.dto.AutoBidRegisterResponse;
import com.vintic.backend.autobid.dto.AutoBidUpdateResponse;
import com.vintic.backend.autobid.proxy.ProxyPriceEngine;
import com.vintic.backend.autobid.repository.AutoBidSettingRepository;
import com.vintic.backend.common.exception.AuctionClosedException;
import com.vintic.backend.common.exception.AutoBidAlreadyExistsException;
import com.vintic.backend.common.exception.AutoBidNotFoundException;
import com.vintic.backend.common.exception.CapNotIncreasedException;
import com.vintic.backend.common.exception.CapTooLowException;
import com.vintic.backend.common.exception.PenaltyRestrictedException;
import com.vintic.backend.common.exception.SellerCannotBidException;
import com.vintic.backend.config.ClockConfig;
import com.vintic.backend.product.domain.Product;
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
@Import({AutoBidCommandService.class, ProxyPriceEngine.class, TestClockConfig.class})
class AutoBidCommandServiceTest {

    @Autowired
    private AutoBidCommandService autoBidCommandService;

    @Autowired
    private AuctionRepository auctionRepository;

    @Autowired
    private AutoBidSettingRepository autoBidSettingRepository;

    @Autowired
    private EntityManager entityManager;

    private User persistUser(String email) {
        User user = User.register(email, email, null);
        entityManager.persist(user);
        return user;
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

    @Test
    void LIVE_경매에_등록하면_ACTIVE가_되고_임시_response_필드를_반환한다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = persistLiveAuction(product);
        flushAndClear();

        AutoBidRegisterResponse response = autoBidCommandService.createAutoBid(auction.getId(), bidder.getId(), 200000L);

        assertThat(response.status()).isEqualTo(AutoBidSettingStatus.ACTIVE);
        assertThat(response.bidOccurred()).isFalse();
        assertThat(response.resultingBidAmount()).isNull();
        assertThat(response.isHighestBidder()).isFalse();
        assertThat(response.currentPrice()).isEqualTo(105000L);
        assertThat(response.minNextBidAmount()).isEqualTo(110000L);
        assertThat(response.minCapAmount()).isEqualTo(110000L);
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
    void 현재_설정이_없으면_수정시_40404에_해당하는_예외가_발생한다() {
        assertThatThrownBy(() -> autoBidCommandService.updateAutoBid(999L, 1L, 100000L))
                .isInstanceOf(AutoBidNotFoundException.class);
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

    @Test
    void POST_등록으로_bidOccurred가_false이면_종료_1분_이내여도_연장되지_않는다() {
        User seller = persistUser("seller@vintic.local");
        User bidder = persistUser("bidder@vintic.local");
        Product product = persistProduct(seller);
        LocalDateTime endAt = fixedNow().plusSeconds(30);
        Auction auction = persistLiveAuctionEndingAt(product, 105000L, endAt);
        flushAndClear();

        // 경쟁자가 없어 실제 응찰이 발생하지 않는다(bidOccurred=false).
        AutoBidRegisterResponse response = autoBidCommandService.createAutoBid(auction.getId(), bidder.getId(), 200000L);

        assertThat(response.bidOccurred()).isFalse();
        Auction reloaded = auctionRepository.findById(auction.getId()).orElseThrow();
        assertThat(reloaded.getExtensionCount()).isZero();
        assertThat(reloaded.getEndAt()).isEqualTo(endAt);
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
}
