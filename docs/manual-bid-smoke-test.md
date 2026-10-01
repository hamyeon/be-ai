# #30 수동 입찰 API — Local MySQL Smoke Test 기록

브랜치: `feat/#30-manual-bid`. `POST /api/auctions/{auctionId}/bids`(수동 입찰) 구현 완료 후, 기존 `@DataJpaTest`(H2) 스위트와 별개로 실제 MySQL 기준으로 단일 요청 correctness를 확인한 기록.

## 환경

- **MySQL**: `mysql:8.4` (`docker-compose.local.yml`과 동일 이미지). Smoke 전용 컨테이너 `autique-smoketest-mysql`, host port **3307**, DB `autique_smoketest`.
- **Spring Boot**: `local` profile, 서버 포트 **8081**.
- **격리**: 기존 개발 환경과 완전히 분리해서 실행했다 — 기존 `backend-backend-1`(8080, dev profile), `backend-redis-1`, Windows native MySQL 서비스(3306), 기존 `autique-local-mysql` 컨테이너, 기존 `autique-mysql-data` volume 전부 미사용·미변경. Smoke 전용 익명 임시 volume만 사용했고 테스트 후 컨테이너와 함께 제거했다.
- 빈 DB에서 `ddl-auto: update`만으로 전체 스키마(FK 포함)가 정상 생성됨을 확인 — Flyway/Liquibase/schema.sql 없음.

## Fixture

- Mock 유저 1/2/3 (`LocalUserSeeder`가 기동 시 자동 시딩)
- Product 1건 (`seller_id=1`)
- Auction 3건, 전부 `status=LIVE, start_price=10000, current_price=10000, bid_increment=5000` — 시나리오별로 auction을 분리했다(같은 auction을 재사용하면 두 번째 요청이 "최고입찰자 재입찰 금지" 규칙에 먼저 걸려 의도한 케이스를 검증할 수 없음).
  - auction 1: 경계값 정상 입찰용, bidder = user 2
  - auction 2: 최소금액 미만용, bidder = user 3
  - auction 3: 판매자 본인 입찰용, seller = user 1

## 결과

| 시나리오 | 요청 | 응답 | Bid 생성 | Auction 상태 변화 |
|---|---|---|---|---|
| 경계값(`currentPrice + bidIncrement`) 정상 입찰 | `POST /auctions/1/bids`, `X-User-Id: 2`, `amount=15000` | **HTTP 201** | `Bid(bidType=MANUAL)` 1건 생성 | `current_price` 10000→15000, `current_winner_id` null→2, **`version` 0→1** |
| 최소 입찰가 미만 | `POST /auctions/2/bids`, `X-User-Id: 3`, `amount=14999` | **HTTP 409**, `error.code=40904` | 없음 | 불변 |
| 판매자 본인 입찰 | `POST /auctions/3/bids`, `X-User-Id: 1`(=seller) | **HTTP 403**, `error.code=40301` | 없음 | 불변 |

HTTP 응답뿐 아니라 매 시나리오마다 `auctions`/`bids` 테이블을 SQL로 직접 재조회해 확인했다. 실패한 두 요청에서는 `auctions` row와 `bids` 건수 모두 요청 전후 동일했고, 성공 요청에서만 `Bid` 1건이 생성되고 해당 `Auction`만 갱신되었다(`@Version` 컬럼도 정상적으로 증가).

## 한계

이 smoke test는 **단일 요청 기준 correctness**(정상/실패 경계, 상태 갱신, 에러 코드 매핑)만 확인한 것이다. 동시성(낙관적 락 충돌), Idempotency, Proxy Bidding 실행은 이번 검증 범위가 아니며, 동시성은 이후 별도 `experiment/no-lock` 브랜치에서 No-lock → `SELECT FOR UPDATE` 순으로 비교할 예정이다.
