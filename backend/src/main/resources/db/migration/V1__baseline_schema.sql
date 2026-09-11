-- V1: baseline SHA 04b0d234b8b57e338c3bf16c27cbcfe9d5789e20(main, 2026-09-07, "#86 머지 완료")
-- 시점에 존재하던 기존 스키마 전체를 재현한다. Day5 신규 분석 Job/Result 스키마(ProductAnalysisJob/
-- AnalysisResult)는 여기 섞지 않는다 - 그건 V2(product_analysis_jobs/analysis_result)의 책임이다.
--
-- 작성 방법: 이 SHA의 18개 @Entity 클래스를 `git show 04b0d234...:<path>`로 읽고(working tree는
-- checkout하지 않음), 로컬 MySQL(autique-local-mysql, ddl-auto=update로 지금까지 누적 생성된 DB)의
-- `SHOW CREATE TABLE`로 실제 컬럼명/타입/인덱스/FK/charset을 대조해서 작성했다. AUTO_INCREMENT
-- 시작값은 로컬 DB에 쌓인 테스트 데이터의 현재 시퀀스일 뿐 스키마의 일부가 아니므로 넣지 않았다
-- (깨끗한 DB에서는 1부터 시작하는 게 맞다).
--
-- 확인 안 된/판단이 필요한 두 가지(추정하지 않고 그대로 뺐다 - 상세 근거는 stage 1 보고에 남김):
--   1. `idempotencies.result_bid_id` - 로컬 DB에는 있지만 이 SHA의 Idempotency.java 엔티티에는
--      해당 필드가 없다(주석: "#32 PLACE_BID 전용 resultBidId 기반 replay는... 제거했다"). 즉
--      더 이전 스키마의 잔존 컬럼으로 보여 V1에 넣지 않았다.
--   2. `auto_bid_settings`의 `uk_auto_bid_setting_auction_user (auction_id, user_id)` UNIQUE -
--      로컬 DB에는 있지만 이 SHA의 AutoBidSetting.java에는 `uk_auto_bid_setting_active_slot`
--      (auction_id, user_id, active_slot) 하나만 선언돼 있다. 이 SHA 이후에 추가된 제약일 가능성이
--      있어 baseline에는 넣지 않았다.

CREATE TABLE users (
    id                     BIGINT       NOT NULL AUTO_INCREMENT,
    bid_restricted_until   DATETIME(6)  NULL,
    created_at             DATETIME(6)  NOT NULL,
    email                  VARCHAR(255) NOT NULL,
    nickname               VARCHAR(255) NOT NULL,
    noshow_count           INT          NOT NULL,
    profile_image_url      VARCHAR(255) NULL,
    kakao_user_id          BIGINT       NULL,
    PRIMARY KEY (id),
    UNIQUE KEY UK6dotkott2kjsp8vw4d0m25fb7 (email),
    UNIQUE KEY UKl8nr2xttq0y7a32r7r7vffosx (kakao_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE products (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    base_market_price   INT           NULL,
    brand               VARCHAR(255)  NULL,
    colorway            VARCHAR(255)  NULL,
    component_status    VARCHAR(255)  NULL,
    condition_grade     VARCHAR(255)  NULL,
    created_at          DATETIME(6)   NULL,
    description         VARCHAR(1000) NULL,
    final_price         INT           NULL,
    model               VARCHAR(255)  NULL,
    price_range         VARCHAR(255)  NULL,
    reason              VARCHAR(1000) NULL,
    recommended_price   INT           NULL,
    size_kr             INT           NULL,
    seller_id           BIGINT        NOT NULL,
    PRIMARY KEY (id),
    KEY FKbgw3lyxhsml3kfqnfr45o0vbj (seller_id),
    CONSTRAINT FKbgw3lyxhsml3kfqnfr45o0vbj FOREIGN KEY (seller_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE product_image_urls (
    product_id  BIGINT        NOT NULL,
    image_url   VARCHAR(1000) NOT NULL,
    KEY FK8cnn3ywnlxdlahpdoj6riblst (product_id),
    CONSTRAINT FK8cnn3ywnlxdlahpdoj6riblst FOREIGN KEY (product_id) REFERENCES products (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE auctions (
    id                  BIGINT NOT NULL AUTO_INCREMENT,
    bid_increment       BIGINT NOT NULL,
    created_at          DATETIME(6) NOT NULL,
    current_price       BIGINT NOT NULL,
    end_at              DATETIME(6) NOT NULL,
    start_at            DATETIME(6) NOT NULL,
    start_price         BIGINT NOT NULL,
    status              ENUM('CANCELED','ENDED','LIVE','SCHEDULED') NOT NULL,
    version             BIGINT NULL,
    current_winner_id   BIGINT NULL,
    product_id          BIGINT NOT NULL,
    extension_count     INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_auction_product (product_id),
    KEY idx_auction_status_end_at (status, end_at),
    KEY idx_auction_status_start_at (status, start_at),
    KEY idx_auction_current_winner (current_winner_id),
    CONSTRAINT FK5o1kpvuu8n7sgxm5cpb6fvbxo FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT FKr0k1oshygpv08uep6ht98k3vj FOREIGN KEY (current_winner_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE bids (
    id          BIGINT NOT NULL AUTO_INCREMENT,
    amount      BIGINT NOT NULL,
    bid_type    ENUM('AUTO','MANUAL') NOT NULL,
    created_at  DATETIME(6) NOT NULL,
    auction_id  BIGINT NOT NULL,
    user_id     BIGINT NOT NULL,
    PRIMARY KEY (id),
    KEY idx_bid_auction_amount (auction_id, amount),
    KEY idx_bid_auction_created_id (auction_id, created_at, id),
    KEY idx_bid_user (user_id),
    CONSTRAINT FKbm89m2gow82dotpnlcp7t3p5f FOREIGN KEY (auction_id) REFERENCES auctions (id),
    CONSTRAINT FKmb21nl8gr3srgnlch3s18oqv9 FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT chk_bid_amount_positive CHECK (amount > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- result_bid_id는 넣지 않았다 - 위 파일 상단 주석 1번 참고.
CREATE TABLE idempotencies (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    created_at         DATETIME(6)  NOT NULL,
    idempotency_key    VARCHAR(255) NOT NULL,
    operation_scope    VARCHAR(255) NOT NULL,
    request_hash       VARCHAR(64)  NOT NULL,
    user_id            BIGINT       NOT NULL,
    response_snapshot  TEXT         NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_idempotency_identity (user_id, operation_scope, idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ai_call_logs (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    analysis_id         BIGINT       NULL,
    call_type           ENUM('EMBEDDING','VISION') NOT NULL,
    completion_tokens   INT          NOT NULL,
    created_at          DATETIME(6)  NOT NULL,
    failure_message     VARCHAR(1000) NULL,
    failure_type        ENUM('API_ERROR','PARSE_ERROR') NULL,
    latency_ms          BIGINT       NOT NULL,
    model_name          VARCHAR(100) NOT NULL,
    prompt_tokens       INT          NOT NULL,
    prompt_version      VARCHAR(20)  NULL,
    request_summary     LONGTEXT     NULL,
    response_body       LONGTEXT     NULL,
    stage               VARCHAR(50)  NULL,
    success              BIT(1)      NOT NULL,
    PRIMARY KEY (id),
    KEY idx_ai_call_analysis (analysis_id, created_at),
    KEY idx_ai_call_type_created (call_type, created_at),
    KEY idx_ai_call_prompt_version (prompt_version, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE product_analysis_session (
    id                     BIGINT      NOT NULL AUTO_INCREMENT,
    completed_at           DATETIME(6) NULL,
    confirmed_input_json   LONGTEXT    NULL,
    failure_message        VARCHAR(1000) NULL,
    failure_stage          ENUM('IMAGE_UPLOAD','PRICING','VISION') NULL,
    pricing_result_json    LONGTEXT    NULL,
    started_at             DATETIME(6) NULL,
    status                 ENUM('AWAITING_USER_CONFIRMATION','COMPLETED','CREATED','IMAGE_UPLOADED','IMAGE_UPLOAD_FAILED','PRICING_FAILED','PRICING_PROCESSING','VISION_FAILED','VISION_PROCESSING') NULL,
    vision_result_json     LONGTEXT    NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE product_analysis_session_image_urls (
    session_id  BIGINT        NOT NULL,
    image_url   VARCHAR(1000) NULL,
    KEY FKjfgkwv70f7apnfhy39inowkag (session_id),
    CONSTRAINT FKjfgkwv70f7apnfhy39inowkag FOREIGN KEY (session_id) REFERENCES product_analysis_session (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE auction_price_audits (
    id                    BIGINT NOT NULL AUTO_INCREMENT,
    after_price           BIGINT NOT NULL,
    applied_rule          ENUM('AUTO_ENTRANT_WINS','AUTO_INCUMBENT_DEFENDS','MANUAL_OVERTAKEN_BY_AUTO','MANUAL_UNCONTESTED','TIE_FIRST_IN_WINS') NOT NULL,
    before_price          BIGINT NOT NULL,
    bid_type              ENUM('AUTO','MANUAL') NOT NULL,
    created_at            DATETIME(6) NOT NULL,
    idempotency_id        BIGINT NULL,
    trigger_type          ENUM('AUTO_BID_CREATE','AUTO_BID_UPDATE','MANUAL_BID','SYSTEM_OPEN') NOT NULL,
    auction_id            BIGINT NOT NULL,
    resulting_winner_id   BIGINT NOT NULL,
    PRIMARY KEY (id),
    KEY idx_auction_price_audit_auction_created (auction_id, created_at),
    KEY FK5ol65n75lyx1sxv5grei7xgxo (resulting_winner_id),
    CONSTRAINT FK5ol65n75lyx1sxv5grei7xgxo FOREIGN KEY (resulting_winner_id) REFERENCES users (id),
    CONSTRAINT FKdp26yjq6kiebl0hn11v6p39c5 FOREIGN KEY (auction_id) REFERENCES auctions (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- uk_auto_bid_setting_auction_user는 넣지 않았다 - 위 파일 상단 주석 2번 참고.
CREATE TABLE auto_bid_settings (
    id           BIGINT NOT NULL AUTO_INCREMENT,
    created_at   DATETIME(6) NOT NULL,
    max_amount   BIGINT NOT NULL,
    status       ENUM('ACTIVE','CANCELED','EXHAUSTED','RESERVED') NOT NULL,
    updated_at   DATETIME(6) NOT NULL,
    auction_id   BIGINT NOT NULL,
    user_id      BIGINT NOT NULL,
    active_slot  BIT(1) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_auto_bid_setting_active_slot (auction_id, user_id, active_slot),
    KEY idx_auto_bid_setting_auction_status (auction_id, status),
    KEY idx_auto_bid_setting_user (user_id),
    CONSTRAINT FK7g45j3pybm4xgrf1nquuwrsvo FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT FKahrv5wlltsm9ejlmw0lio2ilv FOREIGN KEY (auction_id) REFERENCES auctions (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE backup_offers (
    id            BIGINT NOT NULL AUTO_INCREMENT,
    created_at    DATETIME(6) NOT NULL,
    deadline      DATETIME(6) NOT NULL,
    purchase_price BIGINT NOT NULL,
    status        ENUM('ACCEPTED','DECLINED','EXPIRED','WAITING') NOT NULL,
    auction_id    BIGINT NOT NULL,
    candidate_id  BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_backup_offer_auction_candidate (auction_id, candidate_id),
    KEY idx_backup_offer_candidate (candidate_id),
    CONSTRAINT FKb6cc8d88gb37o6vfbbtk72gcx FOREIGN KEY (auction_id) REFERENCES auctions (id),
    CONSTRAINT FKpe9ktltrjcpfr9tiqvoprfim7 FOREIGN KEY (candidate_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE auction_likes (
    id          BIGINT NOT NULL AUTO_INCREMENT,
    created_at  DATETIME(6) NOT NULL,
    auction_id  BIGINT NOT NULL,
    user_id     BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_auction_like_auction_user (auction_id, user_id),
    KEY idx_auction_like_auction (auction_id),
    KEY FK12udt66khq3cl17ggeux5auh2 (user_id),
    CONSTRAINT FK12udt66khq3cl17ggeux5auh2 FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT FK7p1sg12t143ub272s1n00hfom FOREIGN KEY (auction_id) REFERENCES auctions (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE notifications (
    id                   BIGINT NOT NULL AUTO_INCREMENT,
    auction_id           BIGINT NOT NULL,
    body                 VARCHAR(255) NOT NULL,
    business_event_key   VARCHAR(255) NOT NULL,
    created_at           DATETIME(6) NOT NULL,
    read_at              DATETIME(6) NULL,
    resource_id          BIGINT NOT NULL,
    title                VARCHAR(255) NOT NULL,
    type                 ENUM('AUCTION_WON','BACKUP_OFFER_CREATED','PAYMENT_EXPIRED') NOT NULL,
    recipient_id         BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_notification_business_event_key (business_event_key),
    KEY idx_notification_recipient_created (recipient_id, created_at, id),
    KEY idx_notification_recipient_read (recipient_id, read_at),
    CONSTRAINT FKqqnsjxlwleyjbxlmm213jaj3f FOREIGN KEY (recipient_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE orders (
    id                BIGINT NOT NULL AUTO_INCREMENT,
    created_at        DATETIME(6) NOT NULL,
    paid_at           DATETIME(6) NULL,
    payment_deadline  DATETIME(6) NOT NULL,
    purchase_price    BIGINT NOT NULL,
    shipping_fee      BIGINT NOT NULL,
    status            ENUM('CANCELED','PAID','PAYMENT_EXPIRED','PAYMENT_PENDING') NOT NULL,
    total_amount      BIGINT NOT NULL,
    auction_id        BIGINT NOT NULL,
    buyer_id          BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_auction_buyer (auction_id, buyer_id),
    KEY idx_order_buyer (buyer_id),
    CONSTRAINT FK9rcjvsf0m9o1lp0kyseo7oygp FOREIGN KEY (auction_id) REFERENCES auctions (id),
    CONSTRAINT FKhtx3insd5ge6w486omk4fnk54 FOREIGN KEY (buyer_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE penalties (
    id          BIGINT NOT NULL AUTO_INCREMENT,
    created_at  DATETIME(6) NOT NULL,
    type        ENUM('FORFEITED','PAYMENT_EXPIRED') NOT NULL,
    auction_id  BIGINT NOT NULL,
    user_id     BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_penalty_auction_user_type (auction_id, user_id, type),
    KEY idx_penalty_user (user_id),
    CONSTRAINT FK7ank2edo23u9b1b0gb2eetmh1 FOREIGN KEY (auction_id) REFERENCES auctions (id),
    CONSTRAINT FKcg1voa99us9fw9sdh7d6pis27 FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ebay_price (
    id                BIGINT NOT NULL AUTO_INCREMENT,
    box_included      VARCHAR(255) NULL,
    brand             VARCHAR(255) NULL,
    colorway          VARCHAR(255) NULL,
    condition_grade   VARCHAR(255) NULL,
    currency          VARCHAR(255) NULL,
    ebay_price_krw    INT NULL,
    item_url          VARCHAR(255) NULL,
    model_name        VARCHAR(255) NULL,
    price_type        VARCHAR(255) NULL,
    size_kr           INT NULL,
    size_us           DECIMAL(38,2) NULL,
    source            VARCHAR(255) NULL,
    target_id         VARCHAR(255) NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE kream_price (
    id                BIGINT NOT NULL AUTO_INCREMENT,
    brand             VARCHAR(255) NULL,
    collected_date    DATE NULL,
    colorway          VARCHAR(255) NULL,
    condition_grade   VARCHAR(255) NULL,
    kream_price_krw   INT NULL,
    memo              VARCHAR(255) NULL,
    model_name        VARCHAR(255) NULL,
    price_type        VARCHAR(255) NULL,
    product_name      VARCHAR(255) NULL,
    product_url       VARCHAR(255) NULL,
    size_kr           INT NULL,
    target_id         VARCHAR(255) NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE product_vectors (
    product_id    BIGINT NOT NULL,
    dimension     INT NOT NULL,
    source_text   VARCHAR(1000) NOT NULL,
    updated_at    DATETIME(6) NOT NULL,
    vector_bytes  LONGBLOB NOT NULL,
    PRIMARY KEY (product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE user_activity_logs (
    id             BIGINT NOT NULL AUTO_INCREMENT,
    activity_type  ENUM('BID','DWELL','LIKE','VIEW') NOT NULL,
    auction_id     BIGINT NULL,
    created_at     DATETIME(6) NOT NULL,
    dwell_seconds  INT NULL,
    product_id     BIGINT NULL,
    user_id        BIGINT NOT NULL,
    PRIMARY KEY (id),
    KEY idx_activity_user_created (user_id, created_at),
    KEY idx_activity_product (product_id),
    KEY idx_activity_auction (auction_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
