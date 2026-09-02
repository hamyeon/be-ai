package com.vintic.backend.concurrency;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.product.domain.Product;
import com.vintic.backend.product.repository.ProductRepository;
import com.vintic.backend.user.domain.User;
import com.vintic.backend.user.repository.UserRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #74-1 spike: 실제 Spring/JPA/MySQL에서 {@code @Version} conflict가 발생했을 때 attempt
 * transaction 경계(= 이 스파이크의 {@link ConflictSteps#loadHoldThenBid} 호출부) 밖으로
 * 어떤 exception 타입이 최종적으로 전파되는지 실증 확인한다. 이 결과가 #74 Optimistic
 * retry orchestrator의 catch 대상 타입을 결정한다.
 *
 * 실험/no-lock/#34, #35와 동일한 Testcontainers MySQL harness 패턴을 재사용하되, race
 * 재현이 목적이 아니라 "정확히 한 번, 결정적으로" 버전 충돌을 만드는 것이 목적이라
 * CountDownLatch 2개로 두 트랜잭션의 순서를 완전히 고정한다(sleep 기반 아님 - flaky하지 않음).
 *
 * production 코드는 전혀 거치지 않는다(BidCommandService/ManualBidService 미사용) - 순수하게
 * Spring의 JPA 트랜잭션 예외 변환 동작만 확인하는 진단용 테스트라 blocking CI 대상이 아니다
 * (@Tag("experiment") - 단, 이 프로젝트 Gradle 설정에는 아직 excludeTags가 없어 태그만으로
 * 자동 제외되지 않는다. 지금은 --tests로 선택 실행만 한다).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Testcontainers
@Tag("experiment")
class OptimisticLockConflictExceptionSpikeIT {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    // 기존 #34/#35 harness(DelayConfig)와 동일하게 test-only 빈은 @TestConfiguration으로만
    // 등록한다 - component-scan에 의존하지 않는다.
    @TestConfiguration
    static class ConflictStepsConfig {
        @Bean
        ConflictSteps conflictSteps(AuctionRepository auctionRepository, UserRepository userRepository) {
            return new ConflictSteps(auctionRepository, userRepository);
        }
    }

    static class ConflictSteps {

        private final AuctionRepository auctionRepository;
        private final UserRepository userRepository;

        ConflictSteps(AuctionRepository auctionRepository, UserRepository userRepository) {
            this.auctionRepository = auctionRepository;
            this.userRepository = userRepository;
        }

        // "A" 역할: 먼저 읽고(version=0) 신호를 보낸 뒤, "B"가 완전히 커밋할 때까지 대기했다가
        // 자신의(이미 stale해진) in-memory Auction으로 수정을 시도한다 - 새 트랜잭션이므로
        // REQUIRES_NEW로 명시(§B, attempt는 항상 새 트랜잭션).
        @Transactional(propagation = Propagation.REQUIRES_NEW)
        void loadHoldThenBid(Long auctionId, Long bidderId, Long amount, CountDownLatch loaded, CountDownLatch otherDone)
                throws InterruptedException {
            Auction auction = auctionRepository.findById(auctionId).orElseThrow();
            User bidder = userRepository.findById(bidderId).orElseThrow();
            loaded.countDown();
            assertThat(otherDone.await(10, TimeUnit.SECONDS)).isTrue();
            auction.placeManualBid(bidder, amount);
            auctionRepository.save(auction);
            // 메서드 반환 시 프록시가 commit -> flush에서 UPDATE ... WHERE id=? AND version=0 실행,
            // "B"가 이미 version을 1로 올려놨으므로 0 rows affected -> OptimisticLockException 계열.
        }

        // "B" 역할: "A"가 이미 읽은 뒤(신호 수신) 자신은 처음부터 끝까지 정상적으로 완료한다.
        @Transactional(propagation = Propagation.REQUIRES_NEW)
        void loadAndBidImmediately(Long auctionId, Long bidderId, Long amount) {
            Auction auction = auctionRepository.findById(auctionId).orElseThrow();
            User bidder = userRepository.findById(bidderId).orElseThrow();
            auction.placeManualBid(bidder, amount);
            auctionRepository.save(auction);
        }
    }

    @Autowired
    private AuctionRepository auctionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ConflictSteps conflictSteps;

    private Auction persistLiveAuction() {
        User seller = userRepository.save(User.register(
                "spike-seller-" + UUID.randomUUID() + "@vintic.local", "seller", null
        ));
        Product product = productRepository.save(new Product(
                seller,
                List.of("https://example.com/a.jpg"),
                "Nike", "Dunk Low", "Panda", 270, "B", "PARTIAL",
                300000, 350000, "285,000~315,000", 290000, "사유", "설명"
        ));
        Auction auction = Auction.schedule(
                product, 10000L, 5000L, LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1)
        );
        auction.start();
        return auctionRepository.save(auction);
    }

    @Test
    void 동시_트랜잭션이_같은_Auction_row를_stale_version으로_갱신하면_실제로_전파되는_exception_타입을_확인한다()
            throws Exception {
        Auction auction = persistLiveAuction();
        long auctionId = auction.getId();
        long bidderAId = userRepository.save(User.register(
                "spike-bidder-a-" + UUID.randomUUID() + "@vintic.local", "bidderA", null
        )).getId();
        long bidderBId = userRepository.save(User.register(
                "spike-bidder-b-" + UUID.randomUUID() + "@vintic.local", "bidderB", null
        )).getId();

        CountDownLatch loaded = new CountDownLatch(1);
        CountDownLatch otherDone = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        Future<Exception> aOutcome = executor.submit(() -> {
            try {
                conflictSteps.loadHoldThenBid(auctionId, bidderAId, 15000L, loaded, otherDone);
                return null;
            } catch (Exception e) {
                return e;
            }
        });
        Future<Exception> bOutcome = executor.submit(() -> {
            try {
                assertThat(loaded.await(10, TimeUnit.SECONDS)).isTrue();
                conflictSteps.loadAndBidImmediately(auctionId, bidderBId, 20000L);
                return null;
            } finally {
                otherDone.countDown();
            }
        });

        Exception bException = bOutcome.get(30, TimeUnit.SECONDS);
        Exception aException = aOutcome.get(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(bException).as("B(먼저 커밋하는 쪽)는 정상 성공해야 한다").isNull();
        assertThat(aException).as("A(stale version으로 나중에 commit 시도)는 실패해야 한다").isNotNull();

        System.out.println("[spike] A exception class = " + aException.getClass().getName());
        Throwable cause = aException.getCause();
        int depth = 0;
        while (cause != null && depth < 5) {
            System.out.println("[spike] A exception cause[" + depth + "] = " + cause.getClass().getName()
                    + " message=" + cause.getMessage());
            cause = cause.getCause();
            depth++;
        }

        Auction reloaded = auctionRepository.findById(auctionId).orElseThrow();
        System.out.println("[spike] final currentPrice=" + reloaded.getCurrentPrice()
                + " winnerId=" + (reloaded.getCurrentWinner() == null ? null : reloaded.getCurrentWinner().getId())
                + " version=" + reloaded.getVersion());
        // B(20000)만 반영되어야 한다 - A는 commit 자체가 실패해 롤백된다(lost update 없음이 목적이 아니라
        // "어떤 exception이 올라오는가"가 목적이므로, 최종 상태는 참고 로그로만 남긴다).
        assertThat(reloaded.getCurrentPrice()).isEqualTo(20000L);
    }
}
