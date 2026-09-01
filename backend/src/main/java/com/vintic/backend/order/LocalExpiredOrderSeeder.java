package com.vintic.backend.order;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.bid.domain.Bid;
import com.vintic.backend.bid.domain.BidType;
import com.vintic.backend.bid.repository.BidRepository;
import com.vintic.backend.order.domain.Order;
import com.vintic.backend.order.repository.OrderRepository;
import com.vintic.backend.product.domain.Product;
import com.vintic.backend.product.repository.ProductRepository;
import com.vintic.backend.user.domain.User;
import com.vintic.backend.user.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

// FINAL contract §13 "개발/시연용 데이터": PAYMENT_EXPIRED -> 페널티 -> 차순위 제안 흐름을
// 시연하기 위해 paymentDeadline이 이미 지난 PAYMENT_PENDING Order를 하나 미리 넣어둔다.
// OrderExpirationScheduler(#57-2)가 이 Order를 실제로 PAYMENT_EXPIRED로 전이시킨다 - 단,
// payment.expiration.enabled 기본값이 false라(application.yml 참고) 실제로 전이가 보이려면
// PAYMENT_EXPIRATION_ENABLED=true BACKUP_OFFER_EXPIRATION_ENABLED=true도 함께 켜야 한다.
// 강제 만료용 production API는 만들지 않는다(계약 그대로).
//
// **gap 기록**: 계약 문서(§13)는 "local/dev seed"라고 적혀 있지만, 이 클래스는 LocalUserSeeder와
// 동일한 이유로 local에만 적용한다 - dev는 팀이 공유하는 RDS라 더미 낙찰/주문 데이터를 넣으면
// 실제 사용자 데이터와 섞여 오염된다. 계약 문구와의 차이를 임의로 넓히지 않고 여기 기록만 남긴다.
//
// **enabled 기본값이 false인 이유**: 이 프로젝트의 MySqlIT 다수가 @ActiveProfiles("local")을
// 데이터소스 설정 모양만 빌리는 용도로 재사용한다(#56-3 harness부터의 기존 관례) - 기본
// 활성화하면 그 테스트들의 신규 Testcontainers MySQL에도 이 seed Order가 매번 자동 삽입된다.
// 그 자체는 대부분 무해하지만, BackupOfferAcceptAtomicityMySqlIT처럼 orders 테이블 전체에
// `CHECK (1=0)` 같은 제약을 강제로 거는 테스트는 기존 row(이 seed Order)가 있으면 ALTER
// TABLE 자체가 실패해 실제로 깨지는 것을 확인했다. 기존 테스트 파일을 고치는 대신(스코프 확대),
// 이 프로젝트가 이미 쓰는 "위험한 부수효과는 기본 꺼두고 명시적으로 켠다" 패턴(OrderExpirationScheduler
// 참고)을 그대로 따른다.
@Component
@Profile("local")
public class LocalExpiredOrderSeeder implements ApplicationRunner {

    private static final String SEED_BUYER_EMAIL = "seed-expired-buyer@vintic.local";

    private final UserRepository userRepository;
    private final ProductRepository productRepository;
    private final AuctionRepository auctionRepository;
    private final BidRepository bidRepository;
    private final OrderRepository orderRepository;
    private final boolean enabled;

    public LocalExpiredOrderSeeder(
            UserRepository userRepository,
            ProductRepository productRepository,
            AuctionRepository auctionRepository,
            BidRepository bidRepository,
            OrderRepository orderRepository,
            @Value("${local.expired-order-seed.enabled:false}") boolean enabled
    ) {
        this.userRepository = userRepository;
        this.productRepository = productRepository;
        this.auctionRepository = auctionRepository;
        this.bidRepository = bidRepository;
        this.orderRepository = orderRepository;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        // 멱등: 재기동마다 중복 생성하지 않는다 - 이 seed의 buyer가 이미 있으면 전체를 건너뛴다.
        if (userRepository.findAll().stream().anyMatch(u -> SEED_BUYER_EMAIL.equals(u.getEmail()))) {
            return;
        }

        User seller = userRepository.save(User.register("seed-expired-seller@vintic.local", "seed-expired-seller", null));
        User buyer = userRepository.save(User.register(SEED_BUYER_EMAIL, "seed-expired-buyer", null));

        Product product = productRepository.save(new Product(
                seller,
                List.of("https://example.com/seed-expired.jpg"),
                "Nike", "Dunk Low", "Panda", 270, "B", "PARTIAL",
                300000, 350000, "285,000원 ~ 315,000원", 290000, "결제 기한 만료 시연용 seed", "결제 기한 만료 시연용 seed"
        ));

        Auction auction = Auction.schedule(
                product, 10000L, 5000L, LocalDateTime.now().minusHours(3), LocalDateTime.now().minusHours(2)
        );
        auction.start();
        Auction savedAuction = auctionRepository.save(auction);
        savedAuction.placeManualBid(buyer, 30000L);
        bidRepository.save(Bid.place(savedAuction, buyer, 30000L, BidType.MANUAL));
        savedAuction.end();

        orderRepository.save(Order.createForWinner(
                savedAuction, buyer, 30000L, 3000L, LocalDateTime.now().minusMinutes(1)
        ));
    }
}
