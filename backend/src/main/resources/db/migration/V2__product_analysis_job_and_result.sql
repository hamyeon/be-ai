-- V2: 현재 HEAD(d092925, infra/experiment)의 신규 분석 Job/Result 스키마.
-- V1 baseline과 책임을 섞지 않는다 - 이 파일은 ProductAnalysisJob/AnalysisResult만 다룬다.
-- 컬럼 타입은 엔티티 애노테이션(analyze.job.ProductAnalysisJob, analyze.job.AnalysisResult) +
-- Hibernate 6 / MySQLDialect 기본 매핑 규칙(LocalDateTime -> datetime(6), 길이 미지정 String ->
-- varchar(255))을 근거로 작성했다. status는 V1 작성 중 18/18 기존 테이블에서 실측 확인된
-- 이 프로젝트의 실제 관례(@Enumerated(EnumType.STRING) -> 네이티브 MySQL ENUM(...), VARCHAR
-- 아님 - V1 auctions.status/bids.bid_type 등 참고)를 그대로 따랐다. 이 두 테이블은 로컬 DB에
-- ddl-auto=update로 아직 생성된 적이 없어(2026-09-11 기준 미존재, docker exec로 확인) 이
-- 프로젝트 관례를 참고해 작성한 것이며, 깨끗한 MySQL에서 ddl-auto=validate로 최종 검증한다
-- (이번 단계 §4에서 수행 - 여기서 확정하지 않는다).

CREATE TABLE product_analysis_jobs (
    id                     BIGINT       NOT NULL AUTO_INCREMENT,
    user_id                BIGINT       NOT NULL,
    object_key             VARCHAR(255) NOT NULL,
    idempotency_key        VARCHAR(255) NOT NULL,
    status                 ENUM('PENDING','QUEUED','PUBLISH_FAILED','PROCESSING','COMPLETED','FAILED') NOT NULL,
    created_at             DATETIME(6)  NOT NULL,
    updated_at             DATETIME(6)  NOT NULL,
    processing_started_at  DATETIME(6)  NULL,
    worker_id              VARCHAR(255) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_analysis_job_user_idempotency_key UNIQUE (user_id, idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE analysis_result (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    analysis_id  BIGINT       NOT NULL,
    raw_result   LONGTEXT     NOT NULL,
    created_at   DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_analysis_result_analysis_id UNIQUE (analysis_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
