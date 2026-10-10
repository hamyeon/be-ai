-- #127: 분석 세션 취소 기능 스키마 마이그레이션.
--
-- API/Worker가 공유하는 운영 DB에 새 코드를 배포하기 전에 먼저 적용한다. 재실행해도
-- 안전하다(컬럼 존재 여부를 information_schema로 먼저 확인하고, ENUM MODIFY는 매번
-- 전체 정의를 다시 쓰므로 이미 CANCELLED가 포함돼 있어도 같은 정의를 다시 쓸 뿐이다).
--
-- 실행 전 전제: product_analysis_session 테이블이 이미 존재한다(Hibernate ddl-auto:update/
-- create로 최초 생성된 상태). 테이블 자체가 없는 신규 환경에서는 애플리케이션을 한 번
-- 기동해 테이블을 먼저 만든 뒤 이 스크립트를 적용한다.
--
-- 검증 내역(2026-10-10, 이 SQL 그대로 실행):
--   1. mysql:8.4.10 컨테이너에 #127 이전 스키마(cancelled_at/registered_at 없음, status
--      ENUM에 CANCELLED 없음)를 기존 행 1개와 함께 재현한 뒤 이 스크립트를 적용 -
--      에러 없이 완료, SHOW CREATE TABLE 결과가 목표 스키마와 정확히 일치, 기존 행 보존.
--   2. 같은 스크립트를 2회 더 재실행 - 매번 에러 없음(멱등 확인).
--   3. 적용 후 UPDATE ... SET status='CANCELLED' 실제 성공 확인(적용 전에는
--      ERROR 1265: Data truncated for column 'status'로 실패했었음).
--
-- ddl-auto:update(local/dev/api 프로필)는 cancelled_at/registered_at 두 컬럼은 자동으로
-- 추가하지만, status ENUM의 허용값 목록은 갱신하지 않는다(Hibernate가 MySQL 네이티브
-- ENUM 타입의 값 목록 변경을 ddl-auto:update로 처리하지 않음). worker 프로필(ddl-auto:
-- validate)은 둘 다 자동으로 추가/갱신하지 않고, 이 불일치를 기동 시점에 잡아내지도
-- 못한다(검증됨) - 그래서 두 변경 모두 이 스크립트로 명시적으로 적용해야 한다.

DELIMITER $$

DROP PROCEDURE IF EXISTS _add_column_if_not_exists_127$$
CREATE PROCEDURE _add_column_if_not_exists_127(
    IN p_table VARCHAR(64), IN p_column VARCHAR(64), IN p_ddl VARCHAR(512)
)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table AND COLUMN_NAME = p_column
    ) THEN
        SET @ddl := CONCAT('ALTER TABLE ', p_table, ' ADD COLUMN ', p_ddl);
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$

DELIMITER ;

CALL _add_column_if_not_exists_127('product_analysis_session', 'cancelled_at', 'cancelled_at DATETIME(6) NULL');
CALL _add_column_if_not_exists_127('product_analysis_session', 'registered_at', 'registered_at DATETIME(6) NULL');

DROP PROCEDURE _add_column_if_not_exists_127;

-- status ENUM 허용값 목록에 CANCELLED를 추가한다. 기존 11개 값과 컬럼 속성(DEFAULT NULL,
-- nullable)은 그대로 유지하고 'CANCELLED' 하나만 추가한다.
ALTER TABLE product_analysis_session
  MODIFY COLUMN status ENUM(
    'AWAITING_USER_CONFIRMATION','CANCELLED','COMPLETED','CREATED','IMAGE_UPLOADED',
    'IMAGE_UPLOAD_FAILED','PRICING_FAILED','PRICING_PROCESSING','QUEUED','QUEUE_FAILED',
    'VISION_FAILED','VISION_PROCESSING'
  ) DEFAULT NULL;
