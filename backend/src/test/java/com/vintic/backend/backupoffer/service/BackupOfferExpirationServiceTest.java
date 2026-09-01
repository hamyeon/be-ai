package com.vintic.backend.backupoffer.service;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.backupoffer.domain.BackupOffer;
import com.vintic.backend.backupoffer.domain.BackupOfferStatus;
import com.vintic.backend.backupoffer.repository.BackupOfferRepository;
import com.vintic.backend.bid.domain.Bid;
import com.vintic.backend.bid.domain.BidType;
import com.vintic.backend.bid.repository.BidRepository;
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

// FINAL contract §15/§0.10, #57-2(#56 deferred).
@DataJpaTest
@Import({BackupOfferExpirationService.class, TestClockConfig.class})
class BackupOfferExpirationServiceTest {

    private static final LocalDateTime FIXED_NOW = LocalDateTime.ofInstant(TestClockConfig.FIXED_INSTANT, ClockConfig.APP_ZONE);

    @Autowired
    private BackupOfferExpirationService backupOfferExpirationService;

    @Autowired
    private BackupOfferRepository backupOfferRepository;

    @Autowired
    private BidRepository bidRepository;

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

    private Auction persistEndedAuctionWithThreeBidders(User winner, User rank2, User rank3) {
        User seller = persistUser("seller-" + System.nanoTime() + "@vintic.local");
        Product product = persistProduct(seller);
        Auction auction = Auction.schedule(
                product, 10000L, 5000L, LocalDateTime.now().minusHours(2), LocalDateTime.now().minusHours(1)
        );
        auction.start();
        entityManager.persist(auction);
        bidRepository.save(Bid.place(auction, rank3, 15000L, BidType.MANUAL));
        auction.placeManualBid(rank3, 15000L);
        bidRepository.save(Bid.place(auction, rank2, 20000L, BidType.MANUAL));
        auction.placeManualBid(rank2, 20000L);
        bidRepository.save(Bid.place(auction, winner, 25000L, BidType.MANUAL));
        auction.placeManualBid(winner, 25000L);
        auction.end();
        return auction;
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void deadline_이전이면_WAITING이_유지된다() {
        User winner = persistUser("winner@vintic.local");
        User rank2 = persistUser("rank2@vintic.local");
        User rank3 = persistUser("rank3@vintic.local");
        Auction auction = persistEndedAuctionWithThreeBidders(winner, rank2, rank3);
        BackupOffer offer = backupOfferRepository.save(BackupOffer.create(auction, rank2, 20000L));
        flushAndClear();

        backupOfferExpirationService.expireIfDue(offer.getId());

        BackupOffer reloaded = backupOfferRepository.findById(offer.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(BackupOfferStatus.WAITING);
        assertThat(backupOfferRepository.count()).isEqualTo(1);
    }

    @Test
    void deadline_도달하면_EXPIRED로_전이한다() {
        User winner = persistUser("winner@vintic.local");
        User rank2 = persistUser("rank2@vintic.local");
        User rank3 = persistUser("rank3@vintic.local");
        Auction auction = persistEndedAuctionWithThreeBidders(winner, rank2, rank3);
        BackupOffer offer = backupOfferRepository.save(BackupOffer.create(auction, rank2, 20000L));
        ReflectionTestUtils.setField(offer, "deadline", FIXED_NOW.minusMinutes(1));
        entityManager.merge(offer);
        flushAndClear();

        backupOfferExpirationService.expireIfDue(offer.getId());

        BackupOffer reloaded = backupOfferRepository.findById(offer.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(BackupOfferStatus.EXPIRED);
    }

    @Test
    void rank2_제안이_만료되면_rank3에게_새_제안이_1건_생성된다() {
        User winner = persistUser("winner@vintic.local");
        User rank2 = persistUser("rank2@vintic.local");
        User rank3 = persistUser("rank3@vintic.local");
        Auction auction = persistEndedAuctionWithThreeBidders(winner, rank2, rank3);
        BackupOffer offer = backupOfferRepository.save(BackupOffer.create(auction, rank2, 20000L));
        ReflectionTestUtils.setField(offer, "deadline", FIXED_NOW.minusMinutes(1));
        entityManager.merge(offer);
        flushAndClear();

        backupOfferExpirationService.expireIfDue(offer.getId());

        assertThat(backupOfferRepository.count()).isEqualTo(2);
        BackupOffer nextOffer = backupOfferRepository.findByAuctionIdAndCandidateId(auction.getId(), rank3.getId())
                .orElseThrow();
        assertThat(nextOffer.getStatus()).isEqualTo(BackupOfferStatus.WAITING);
        assertThat(nextOffer.getPurchasePrice()).isEqualTo(15000L);
    }

    @Test
    void rank3_제안이_만료되면_추가_제안이_생성되지_않는다() {
        User winner = persistUser("winner@vintic.local");
        User rank2 = persistUser("rank2@vintic.local");
        User rank3 = persistUser("rank3@vintic.local");
        Auction auction = persistEndedAuctionWithThreeBidders(winner, rank2, rank3);
        BackupOffer offer = backupOfferRepository.save(BackupOffer.create(auction, rank3, 15000L));
        ReflectionTestUtils.setField(offer, "deadline", FIXED_NOW.minusMinutes(1));
        entityManager.merge(offer);
        flushAndClear();

        backupOfferExpirationService.expireIfDue(offer.getId());

        assertThat(backupOfferRepository.count()).isEqualTo(1);
    }

    @Test
    void ACCEPTED된_제안은_재처리하지_않는다() {
        User winner = persistUser("winner@vintic.local");
        User rank2 = persistUser("rank2@vintic.local");
        User rank3 = persistUser("rank3@vintic.local");
        Auction auction = persistEndedAuctionWithThreeBidders(winner, rank2, rank3);
        BackupOffer offer = backupOfferRepository.save(BackupOffer.create(auction, rank2, 20000L));
        offer.accept();
        ReflectionTestUtils.setField(offer, "deadline", FIXED_NOW.minusMinutes(1));
        entityManager.merge(offer);
        flushAndClear();

        backupOfferExpirationService.expireIfDue(offer.getId());

        BackupOffer reloaded = backupOfferRepository.findById(offer.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(BackupOfferStatus.ACCEPTED);
        assertThat(backupOfferRepository.count()).isEqualTo(1);
    }

    @Test
    void DECLINED된_제안은_재처리하지_않는다() {
        User winner = persistUser("winner@vintic.local");
        User rank2 = persistUser("rank2@vintic.local");
        User rank3 = persistUser("rank3@vintic.local");
        Auction auction = persistEndedAuctionWithThreeBidders(winner, rank2, rank3);
        BackupOffer offer = backupOfferRepository.save(BackupOffer.create(auction, rank2, 20000L));
        offer.decline();
        ReflectionTestUtils.setField(offer, "deadline", FIXED_NOW.minusMinutes(1));
        entityManager.merge(offer);
        flushAndClear();

        backupOfferExpirationService.expireIfDue(offer.getId());

        BackupOffer reloaded = backupOfferRepository.findById(offer.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(BackupOfferStatus.DECLINED);
        assertThat(backupOfferRepository.count()).isEqualTo(1);
    }

    @Test
    void 이미_EXPIRED된_제안은_재처리해도_다음_제안을_중복_생성하지_않는다() {
        User winner = persistUser("winner@vintic.local");
        User rank2 = persistUser("rank2@vintic.local");
        User rank3 = persistUser("rank3@vintic.local");
        Auction auction = persistEndedAuctionWithThreeBidders(winner, rank2, rank3);
        BackupOffer offer = backupOfferRepository.save(BackupOffer.create(auction, rank2, 20000L));
        ReflectionTestUtils.setField(offer, "deadline", FIXED_NOW.minusMinutes(1));
        entityManager.merge(offer);
        flushAndClear();

        backupOfferExpirationService.expireIfDue(offer.getId());
        backupOfferExpirationService.expireIfDue(offer.getId());
        backupOfferExpirationService.expireIfDue(offer.getId());

        assertThat(backupOfferRepository.count()).isEqualTo(2); // rank2(EXPIRED) + rank3(WAITING), 중복 없음.
    }

    @Test
    void 존재하지_않는_제안을_처리해도_예외없이_아무일도_일어나지_않는다() {
        backupOfferExpirationService.expireIfDue(9999L);

        assertThat(backupOfferRepository.count()).isZero();
    }
}
