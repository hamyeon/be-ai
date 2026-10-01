package com.vintic.backend.concurrency;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.backupoffer.domain.BackupOffer;
import com.vintic.backend.backupoffer.dto.BackupOfferDeclineResponse;
import com.vintic.backend.backupoffer.repository.BackupOfferRepository;
import com.vintic.backend.bid.domain.Bid;
import com.vintic.backend.bid.domain.BidType;
import com.vintic.backend.bid.repository.BidRepository;
import com.vintic.backend.common.dto.ApiResponse;
import com.vintic.backend.product.domain.Product;
import com.vintic.backend.product.repository.ProductRepository;
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
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// #56-3: decline이 실제 MySQL/실제 HTTP 스택을 통해서도 rank2 -> rank3 체이닝, rank3(마지막
// 후보) decline 시 추가 offer 없음을 정확히 만드는지 end-to-end로 확인한다. 순수 선정 로직 자체는
// BackupCandidateSelectorTest/BackupOfferCommandServiceTest(H2)에서 이미 검증했다 - 여기서는
// 컨트롤러/Idempotency 배관을 거치지 않는 decline 경로가 실제 MySQL에서도 동일하게 동작하는지만
// 재확인한다(중복 재작성하지 않음).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@Testcontainers
class BackupOfferDeclineChainMySqlIT {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private AuctionRepository auctionRepository;

    @Autowired
    private BidRepository bidRepository;

    @Autowired
    private BackupOfferRepository backupOfferRepository;

    private Auction persistEndedAuctionWithThreeBidders(User winner, User rank2, User rank3) {
        User seller = userRepository.save(User.register("seller-" + System.nanoTime() + "@vintic.local", "seller", null));
        Product product = productRepository.save(new Product(
                seller,
                List.of("https://example.com/a.jpg"),
                "Nike", "Dunk Low", "Panda", 270, "B", "PARTIAL",
                300000, 350000, "285,000원 ~ 315,000원", 290000, "사유", "설명"
        ));
        Auction auction = Auction.schedule(
                product, 10000L, 5000L, LocalDateTime.now().minusHours(2), LocalDateTime.now().minusHours(1)
        );
        auction.start();
        Auction savedAuction = auctionRepository.save(auction);
        bidRepository.save(Bid.place(savedAuction, rank3, 15000L, BidType.MANUAL));
        savedAuction.placeManualBid(rank3, 15000L);
        bidRepository.save(Bid.place(savedAuction, rank2, 20000L, BidType.MANUAL));
        savedAuction.placeManualBid(rank2, 20000L);
        bidRepository.save(Bid.place(savedAuction, winner, 25000L, BidType.MANUAL));
        savedAuction.placeManualBid(winner, 25000L);
        savedAuction.end();
        return savedAuction;
    }

    private HttpEntity<Void> declineRequest(Long userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", String.valueOf(userId));
        return new HttpEntity<>(headers);
    }

    @Test
    void rank2가_거절하면_rank3에게_새_제안이_1건_생성된다() {
        User winner = userRepository.save(User.register("winner-" + System.nanoTime() + "@vintic.local", "winner", null));
        User rank2 = userRepository.save(User.register("rank2-" + System.nanoTime() + "@vintic.local", "rank2", null));
        User rank3 = userRepository.save(User.register("rank3-" + System.nanoTime() + "@vintic.local", "rank3", null));
        Auction auction = persistEndedAuctionWithThreeBidders(winner, rank2, rank3);
        BackupOffer offer = backupOfferRepository.save(BackupOffer.create(auction, rank2, 20000L));

        ResponseEntity<ApiResponse<BackupOfferDeclineResponse>> response = restTemplate.exchange(
                "/api/backup-offers/" + offer.getId() + "/decline", HttpMethod.POST, declineRequest(rank2.getId()),
                new ParameterizedTypeReference<>() {
                }
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().data().status().name()).isEqualTo("DECLINED");

        long offerCount = backupOfferRepository.findAll().stream()
                .filter(o -> o.getAuction().getId().equals(auction.getId()))
                .count();
        assertThat(offerCount).isEqualTo(2);
        assertThat(backupOfferRepository.findByAuctionIdAndCandidateId(auction.getId(), rank3.getId())).isPresent();
    }

    @Test
    void rank3의_거절에는_추가_제안이_생성되지_않는다() {
        User winner = userRepository.save(User.register("winner-" + System.nanoTime() + "@vintic.local", "winner", null));
        User rank2 = userRepository.save(User.register("rank2-" + System.nanoTime() + "@vintic.local", "rank2", null));
        User rank3 = userRepository.save(User.register("rank3-" + System.nanoTime() + "@vintic.local", "rank3", null));
        Auction auction = persistEndedAuctionWithThreeBidders(winner, rank2, rank3);
        BackupOffer offer = backupOfferRepository.save(BackupOffer.create(auction, rank3, 15000L));

        ResponseEntity<ApiResponse<BackupOfferDeclineResponse>> response = restTemplate.exchange(
                "/api/backup-offers/" + offer.getId() + "/decline", HttpMethod.POST, declineRequest(rank3.getId()),
                new ParameterizedTypeReference<>() {
                }
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        long offerCount = backupOfferRepository.findAll().stream()
                .filter(o -> o.getAuction().getId().equals(auction.getId()))
                .count();
        assertThat(offerCount).isEqualTo(1);
    }
}
