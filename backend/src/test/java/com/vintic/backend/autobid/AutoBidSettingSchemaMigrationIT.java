package com.vintic.backend.autobid;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.autobid.domain.AutoBidSetting;
import com.vintic.backend.autobid.repository.AutoBidSettingRepository;
import com.vintic.backend.product.domain.Product;
import com.vintic.backend.product.repository.ProductRepository;
import com.vintic.backend.user.domain.User;
import com.vintic.backend.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// #41 gap 검증: ddl-auto:update가 기존 UNIQUE(auction_id, user_id) 제약을
// UNIQUE(auction_id, user_id, active_slot)로 안전하게 교체해준다는 보장이 없다는 문서화된
// 한계를, "#40/#41 이전 스키마가 이미 떠 있던 DB"를 흉내내 실제로 확인한다.
// @BeforeAll static 메서드는 Spring context(=Hibernate ddl-auto)가 뜨기 전에 실행되므로,
// 여기서 raw JDBC로 옛 스키마를 미리 만들어두면 이후 context 기동 시 ddl-auto:update가
// 그 위에서 무엇을 하는지(구 제약을 지우는지 안 지우는지) 있는 그대로 관찰할 수 있다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Testcontainers
class AutoBidSettingSchemaMigrationIT {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @BeforeAll
    static void seedPreMigrationSchema() throws Exception {
        try (Connection connection = DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
             Statement statement = connection.createStatement()) {
            // #41 이전 스키마를 최소한으로 재현한다 - 실제 FK까지 완전히 재현할 필요 없이,
            // "UNIQUE(auction_id, user_id) 제약이 이미 존재하는 auto_bid_settings 테이블"이라는
            // 핵심 조건만 갖추면 된다. Hibernate가 이후 컬럼(max_amount 등)을 추가/보정하는 것도
            // ddl-auto:update의 정상 동작이므로 여기서는 최소 컬럼만 만든다.
            statement.execute("""
                    CREATE TABLE auto_bid_settings (
                        id BIGINT NOT NULL AUTO_INCREMENT,
                        auction_id BIGINT NOT NULL,
                        user_id BIGINT NOT NULL,
                        max_amount BIGINT NOT NULL,
                        status VARCHAR(255) NOT NULL,
                        created_at DATETIME NOT NULL,
                        updated_at DATETIME NOT NULL,
                        PRIMARY KEY (id),
                        UNIQUE KEY uk_auto_bid_setting_auction_user (auction_id, user_id)
                    )
                    """);
        }
    }

    @Autowired
    private AuctionRepository auctionRepository;

    @Autowired
    private AutoBidSettingRepository autoBidSettingRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProductRepository productRepository;

    private Set<String> currentUniqueConstraintNames() throws Exception {
        Set<String> names = new HashSet<>();
        try (Connection connection = DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT DISTINCT CONSTRAINT_NAME
                     FROM information_schema.TABLE_CONSTRAINTS
                     WHERE TABLE_SCHEMA = DATABASE()
                       AND TABLE_NAME = 'auto_bid_settings'
                       AND CONSTRAINT_TYPE = 'UNIQUE'
                     """)) {
            while (resultSet.next()) {
                names.add(resultSet.getString("CONSTRAINT_NAME"));
            }
        }
        return names;
    }

    // 이 테스트는 "새 제약이 안전하게 만들어진다"를 주장하지 않는다
    // ddl-auto:update가 실제로 무엇을 남겼는지를 있는 그대로 기록한다. 아래 두 결과 중 어느 쪽이 나오든 모두
    // 이 테스트 자체는 "관찰"이 목적이므로 실패로 처리하지 않고, 그 결과에 따라 뒤이은
    // 기능 테스트(CANCELED 다건 저장)로 실제 영향을 검증한다.
    @Test
    void ddl_auto_update가_기존_UNIQUE_auction_user_제약을_실제로_지우는지_관찰한다() throws Exception {
        Set<String> constraints = currentUniqueConstraintNames();

        boolean oldConstraintRemains = constraints.contains("uk_auto_bid_setting_auction_user");
        boolean newConstraintCreated = constraints.contains("uk_auto_bid_setting_active_slot");

        // 관찰 결과를 그대로 문서화 근거로 남긴다(docs/api/auction-api-contract-gap.md 참고).
        System.out.println("[schema-migration-check] constraints=" + constraints
                + ", oldConstraintRemains=" + oldConstraintRemains
                + ", newConstraintCreated=" + newConstraintCreated);

        assertThat(newConstraintCreated)
                .as("ddl-auto:update가 최소한 새 제약은 추가해야 한다 - 추가하지 않았다면 그 자체가 더 큰 문제다")
                .isTrue();
    }

    @Test
    void 구_제약이_남아있으면_CANCELED_이력_다건_저장이_실제로_막힌다() throws Exception {
        boolean oldConstraintRemains = currentUniqueConstraintNames().contains("uk_auto_bid_setting_auction_user");

        User seller = userRepository.save(User.register("seller-migration@vintic.local", "seller", null));
        User bidder = userRepository.save(User.register("bidder-migration@vintic.local", "bidder", null));
        Product product = productRepository.save(new Product(
                seller,
                List.of("https://example.com/a.jpg"),
                "Nike", "Dunk Low", "Panda", 270, "B", "PARTIAL",
                300000, 350000, "285,000원 ~ 315,000원", 290000, "사유", "설명"
        ));
        Auction auction = Auction.schedule(
                product, 10000L, 5000L, LocalDateTime.now().plusHours(1), LocalDateTime.now().plusHours(2)
        );
        auctionRepository.save(auction);

        AutoBidSetting first = AutoBidSetting.reserve(auction, bidder, 100000L);
        first.cancel();
        autoBidSettingRepository.saveAndFlush(first);

        AutoBidSetting second = AutoBidSetting.reserve(auction, bidder, 120000L);
        second.cancel();

        if (oldConstraintRemains) {
            // 이 브랜치가 실행된다면 문서에 기록한 migration limitation이 실제로 기능을 깨뜨린다는
            // 뜻이다 - 공유 dev/local DB에서는 반드시 수동 정리가 필요하다(gap 문서의 SQL 런북 참고).
            assertThatThrownBy(() -> autoBidSettingRepository.saveAndFlush(second))
                    .isInstanceOf(DataIntegrityViolationException.class);
        } else {
            autoBidSettingRepository.saveAndFlush(second);
            assertThat(autoBidSettingRepository.findAll()).hasSize(2);
        }
    }
}
