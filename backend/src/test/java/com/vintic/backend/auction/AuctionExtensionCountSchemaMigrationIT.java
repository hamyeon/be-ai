package com.vintic.backend.auction;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.product.domain.Product;
import com.vintic.backend.product.repository.ProductRepository;
import com.vintic.backend.user.domain.User;
import com.vintic.backend.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// #43 gap 검증: extension_count 컬럼을 기존 auctions 테이블(#43 이전 스키마, 컬럼 자체가 없음)에
// ddl-auto:update로 추가할 때 - (1) 기존 row가 NULL이 아니라 0으로 채워지는지, (2) 컬럼 자체가
// NOT NULL로 정상 추가되는지, (3) 이후 신규 row도 0으로 저장되는지를 실제 MySQL(Testcontainers)로
// 확인한다. AutoBidSettingSchemaMigrationIT와 동일한 패턴 - @BeforeAll(Spring context 기동 전)에서
// raw JDBC로 옛 스키마 + 기존 row를 미리 만들어두고, 이후 ddl-auto:update가 실제로 무엇을
// 남기는지 관찰한다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Testcontainers
class AuctionExtensionCountSchemaMigrationIT {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    private static final long PRE_EXISTING_AUCTION_ID = 1L;

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
            // #43 이전 auctions 스키마를 재현한다 - extension_count 컬럼이 아예 없다.
            // product_id는 FK 없이 임의값으로 채운다(auto_bid_settings 마이그레이션 IT와 동일하게,
            // 이 테스트의 관심사는 FK 무결성이 아니라 extension_count 컬럼 마이그레이션 자체다).
            statement.execute("""
                    CREATE TABLE auctions (
                        id BIGINT NOT NULL AUTO_INCREMENT,
                        product_id BIGINT NOT NULL,
                        current_winner_id BIGINT NULL,
                        start_price BIGINT NOT NULL,
                        current_price BIGINT NOT NULL,
                        bid_increment BIGINT NOT NULL,
                        start_at DATETIME NOT NULL,
                        end_at DATETIME NOT NULL,
                        status VARCHAR(255) NOT NULL,
                        created_at DATETIME NOT NULL,
                        PRIMARY KEY (id)
                    )
                    """);
            statement.execute("""
                    INSERT INTO auctions
                        (id, product_id, current_winner_id, start_price, current_price, bid_increment,
                         start_at, end_at, status, created_at)
                    VALUES
                        (%d, 999, NULL, 10000, 10000, 5000,
                         '2026-01-01 00:00:00', '2026-01-01 02:00:00', 'LIVE', '2026-01-01 00:00:00')
                    """.formatted(PRE_EXISTING_AUCTION_ID));
        }
    }

    @Autowired
    private AuctionRepository auctionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProductRepository productRepository;

    private boolean columnIsNotNullWithZeroDefault() throws Exception {
        try (Connection connection = DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT IS_NULLABLE, COLUMN_DEFAULT
                     FROM information_schema.COLUMNS
                     WHERE TABLE_SCHEMA = DATABASE()
                       AND TABLE_NAME = 'auctions'
                       AND COLUMN_NAME = 'extension_count'
                     """)) {
            assertThat(resultSet.next())
                    .as("extension_count 컬럼이 ddl-auto:update로 추가돼 있어야 한다")
                    .isTrue();
            String isNullable = resultSet.getString("IS_NULLABLE");
            String columnDefault = resultSet.getString("COLUMN_DEFAULT");
            System.out.println("[extension-count-migration-check] IS_NULLABLE=" + isNullable
                    + ", COLUMN_DEFAULT=" + columnDefault);
            return "NO".equals(isNullable) && "0".equals(columnDefault);
        }
    }

    @Test
    void ddl_auto_update가_extension_count_컬럼을_NOT_NULL_DEFAULT_0으로_추가한다() throws Exception {
        assertThat(columnIsNotNullWithZeroDefault()).isTrue();
    }

    @Test
    void 마이그레이션_이전에_존재하던_row의_extension_count는_NULL이_아니라_0으로_채워진다() throws Exception {
        Auction reloaded = auctionRepository.findById(PRE_EXISTING_AUCTION_ID).orElseThrow();

        assertThat(reloaded.getExtensionCount()).isZero();
    }

    @Test
    void 마이그레이션_이후_신규_row도_extension_count가_0으로_저장된다() {
        User seller = userRepository.save(User.register("seller-ext-migration@vintic.local", "seller", null));
        Product product = productRepository.save(new Product(
                seller,
                List.of("https://example.com/a.jpg"),
                "Nike", "Dunk Low", "Panda", 270, "B", "PARTIAL",
                300000, 350000, "285,000원 ~ 315,000원", 290000, "사유", "설명"
        ));
        Auction auction = Auction.schedule(
                product, 10000L, 5000L, LocalDateTime.now().plusHours(1), LocalDateTime.now().plusHours(2)
        );

        Auction saved = auctionRepository.saveAndFlush(auction);

        assertThat(saved.getExtensionCount()).isZero();
        Auction reloaded = auctionRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getExtensionCount()).isZero();
    }
}
