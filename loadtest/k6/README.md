# hot-auction k6 부하 테스트

같은 LIVE Auction 하나에 동시 bidder가 몰릴 때, 현재 production의 비관적 락
(`AuctionRepository.findByIdForUpdate()` + `@Lock(PESSIMISTIC_WRITE)`, `BidCommandService`)
구조가 어디까지 버티는지 실제 HTTP 경로(k6 → Spring API → HikariCP → MySQL)로 측정한다.

**production 동시성 로직은 이 부하 테스트를 위해 전혀 바꾸지 않았다.** `AuctionRepository`/
`BidCommandService`/`Auction`/`GlobalExceptionHandler`는 무변경이다. HikariCP
`maximum-pool-size`도 이 baseline에서는 건드리지 않는다(§7).

## 0. 사전 준비

1. 로컬 Spring Boot 앱을 **local 프로필**로 기동한다(IntelliJ 등, 기본 `:8080`).
2. [k6](https://k6.io/docs/get-started/installation/)를 설치한다(이 저장소에 포함하지 않음).
3. `LOCAL_DB_PASSWORD`(로컬 MySQL root 비밀번호)를 알고 있어야 한다.

## 1. run 간 상태 격리 (가장 중요)

**seeder를 1번만 실행하고 같은 Auction을 계속 재사용하지 않는다.** 그러면 stage/반복마다
`currentPrice`/`Bid`가 누적돼, 나중 run일수록 (a) k6가 계산하는 입찰 금액(`startPrice+
increment*VU`, seed 시점 값 기준)이 실제 `currentPrice`보다 낮아져 전부 `BID_AMOUNT_TOO_LOW`로
거절되거나 (b) invariant 검증이 이전 run의 Bid까지 같이 보게 된다.

그래서 seeder를 **users(1회)** 와 **round(매 run마다)** 로 나눴다.

| 클래스 | 실행 시점 | 하는 일 |
|---|---|---|
| `HotAuctionUserSeeder` | 1회(전체 실험 시작 전) | seller 1명 + bidder 풀(기본 200명) 생성 → `data/hot-auction-users.json` |
| `HotAuctionRoundSeeder` | **매 run(stage x repetition)마다** | 그 users 풀을 재사용해 **완전히 새로운** hot auction + unrelated auction 생성 → `data/hot-auction-seed.json` |
| `HotAuctionInvariantCheck` | 매 run 직후 | `hot-auction-seed.json`의 auctionId **하나만** 검사 |

"기존 auction을 UPDATE로 리셋"하지 않고 "매번 새 auction row"를 쓰는 이유: reset은 그
auction을 가리키는 과거 Bid/Idempotency row가 여전히 DB에 남아있어 "리셋된 auction인데
과거 Bid가 딸려있는" 모순 상태를 만들 위험이 있다. 매번 새 `auctionId`를 쓰면 Bid/
Idempotency 조회가 전부 `auctionId` 하나로 스코프되므로(§5) 과거 run과 절대 섞이지 않고,
별도 정리(TRUNCATE 등)도 필요 없다.

unrelated auction도 매 run마다 **같은 고정 초기 조건**(startPrice=50000,
bidIncrement=3000, 시스템 프로퍼티로 바꾸지 않음)으로 새로 만든다 - hot auction 워크로드
크기와 무관하게 stage 간 unrelated GET latency 비교 기준이 동일하게 유지되도록.

`run-stages.sh`가 이 순서(reseed round → k6 → invariant check)를 매 iteration마다
자동으로 수행한다 - 수동으로 실행할 때는 §2/§4를 그대로 따라야 한다.

## 2. 1회: user 풀 생성

```bash
cd backend
LOCAL_DB_PASSWORD=<로컬 MySQL 비밀번호> ./gradlew.bat test \
  --tests "com.vintic.backend.loadtest.HotAuctionUserSeeder" -DbidderCount=200
```

## 3. 인증 경계 - local 전용이며, dev/prod/AWS에 그대로 쓸 수 없다

이 harness(`X-User-Id` 헤더, `MockAuthInterceptor`, `MockUserRegistry`)는 **local 프로필
baseline 전용이다.** dev/prod/AWS에서는 `X-User-Id`가 인증 수단이 **아니다** -
`JwtSecurityConfig`/`JwtAuthenticationFilter`가 `Authorization: Bearer <JWT>`만 신뢰하고,
`MockAuthInterceptor` 자체가 `!dev & !prod` profile에서만 활성화된다(`X-User-Id`를 보내도
그냥 무시되거나 401이 난다). **이 스크립트를 dev/prod/AWS 엔드포인트에 그대로 실행할 수
있다고 착각하지 말 것.**

### AWS 실험용 인증 — 제안만, 구현하지 않음

production에 auth bypass(예: 헤더만으로 인증되는 별도 경로)를 추가하지 않는다. 대신 **기존
JWT 발급 구조를 그대로 재사용**해 seeded load-test user용 Access Token을 부하 테스트 실행
"전에" 미리 만들어 두는 방법을 조사했다:

- `JwtTokenProvider.issueAccessToken(Long userId)`(`backend/src/main/java/.../auth/jwt/
  JwtTokenProvider.java`)가 이미 존재한다. `subject=userId`, `HS256`, 실제 서버와 동일한
  `jwt.secret`/`access-ttl-seconds`로 서명하므로, 이걸로 만든 토큰은 `JwtAuthenticationFilter`
  입장에서 실제 로그인으로 발급된 토큰과 구분되지 않는다(= bypass가 아니라 같은 발급
  경로를 test 코드에서 그대로 호출하는 것).
- 제안하는 절차(둘 다 **구현하지 않음**, production 코드 변경 없음):
  1. AWS RDS(또는 대상 dev DB)에 이번 실험용 seller/bidder `users` row를 별도로 미리 준비한다
     (그 환경에 접근 권한이 있는 사람이, 공유 DB이므로 더미 row를 함부로 넣지 않는다는
     `LocalUserSeeder` 주석의 원칙과 동일하게 신중히 처리 - 이번 세션에서 실행하지 않았다).
  2. 그 userId들에 대해, 대상 환경과 **동일한 `JWT_SECRET`**(AWS Secrets Manager/SSM 등에서
     읽고 리포지토리에 커밋하거나 로그에 남기지 않음)을 넣은 `JwtTokenProvider`를 test 소스의
     별도 도구(JUnit 클래스 또는 작은 CLI)에서 직접 생성해 `issueAccessToken(userId)`를 호출,
     각 bidder의 Access Token을 `hot-auction-seed.json`과 비슷한 JSON에 `accessToken`
     필드로 저장한다.
  3. k6 스크립트는 대상이 AWS/dev일 때만 `X-User-Id` 대신 `Authorization: Bearer <token>`
     헤더를 쓰도록 분기한다(예: `__ENV.AUTH_MODE=jwt`).
- 제약/위험: (a) 기본 Access Token TTL이 30분(`jwt.access-ttl-seconds` 기본값)이라 그보다
  긴 실험은 재발급이 필요하다, (b) `JWT_SECRET`을 로컬 도구가 알아야 하므로 취급에 주의가
  필요하다(레포에 커밋 금지), (c) AWS 쪽 users seed 자체가 공유 인프라 변경이라 별도 승인이
  필요하다.

## 4. Smoke test — 정확히 이 순서로 (본격 실행 전 필수)

```bash
cd loadtest/k6
LOCAL_DB_PASSWORD=<로컬 MySQL 비밀번호> ./smoke-test.sh
```

내부적으로 정확히 이 순서를 수행한다(수동으로 할 때도 이 순서를 지킬 것):

1. **Seeder 실제 실행** — `HotAuctionUserSeeder`(없으면) + `HotAuctionRoundSeeder`(항상, fresh auction)
2. **서버 재시작** — actuator 설정이 최근 추가돼 이미 떠 있던 프로세스에는 반영 안 돼 있을
   수 있다. 스크립트가 여기서 멈추고 Enter를 기다린다.
3. **`/actuator/health` 확인** — 200이 아니면 즉시 중단
4. **Hikari metric endpoint 확인** — `hikaricp.connections.{active,pending,max}` 3개 모두
   200이어야 통과
5. **8 VU k6 실행**
6. **post-state invariant check 실행**

모두 통과해야 §6(stage×반복 본실행)로 넘어간다.

## 5. Invariant 검사 범위 — 이번 run의 auctionId 하나만

`HotAuctionInvariantCheck`는 `hot-auction-seed.json`에 적힌 **이번 run 전용 auctionId**로만
`Bid`(`findByAuctionIdOrderByCreatedAtDescIdDesc`)와 `idempotencies`
(`operation_scope='PLACE_BID:{auctionId}'`)를 조회한다 - 전체 DB나 다른 run/다른 테스트의
데이터를 보지 않는다. `operation_scope` 문자열 자체가 그 auctionId를 담고 있어서 다른
auction의 idempotency row는 애초에 이 WHERE절과 일치할 수 없다. 이 격리는 **§1의 "매 run마다
새 auctionId"** 전제 위에서만 성립한다 - 같은 auction을 여러 run이 재사용하면 이 스코프
경계가 무너진다(그래서 §1 변경이 선행되어야 했다).

## 6. stage × 반복 본실행

```bash
cd loadtest/k6
LOCAL_DB_PASSWORD=<비번> STAGES="8,20,50" REPEATS=1 ./run-stages.sh
# 최종 실험: LOCAL_DB_PASSWORD=<비번> STAGES="8,20,50,100,200" REPEATS=3 ./run-stages.sh
```

- k6 스크립트 자체는 "한 stage 1회"만 담당한다(`per-vu-iterations`, VU=`$VUS`,
  iterations=1). stage 목록과 반복 횟수, 그리고 매 iteration 전 fresh auction 시딩은
  `run-stages.sh`가 담당한다(§1).
- 각 실행마다 `results/summary-vu{N}-run{n}.json`, `results/seed-vu{N}-run{n}.json`,
  `results/invariant-vu{N}-run{n}.log`가 남고, 전체가 끝나면
  `node summarize.js results/summary-*.json`이 자동으로 비교표를 출력한다.
- **200 VU까지 한 번에 실행하지 마라.** §4 smoke test(8 VU)를 먼저 통과시키고, 8 → 20 → 50
  순으로 손으로 늘려가며 결과를 본 뒤 100/200을 시도한다.

## 7. HikariCP 관측과 병목 구분 (설정 변경 없음, 관측만)

이 프로젝트는 `application*.yml` 어디에도 `spring.datasource.hikari.*`를 설정하지 않는다 -
**HikariCP 기본값(`maximum-pool-size=10`)을 이번 baseline에서는 그대로 둔다.** 임의로 올리지
않는다 - 그 값 자체가 운영 SLO에 영향을 주는 결정이라 이 부하 테스트 결과를 보고 나서 별도로
논의할 사안이다(§8 제안 참고).

테스트 실행 중 별도 터미널에서:

```bash
while true; do curl -s http://localhost:8080/actuator/metrics/hikaricp.connections.active | python3 -m json.tool; sleep 1; done
while true; do curl -s http://localhost:8080/actuator/metrics/hikaricp.connections.pending | python3 -m json.tool; sleep 1; done
curl -s http://localhost:8080/actuator/metrics/hikaricp.connections.max | python3 -m json.tool
```

### 100 VU에서 "pool 대기"인지 "DB row lock 대기"인지 구분하는 방법

k6가 분류하는 두 카운터를 위 Hikari 관측과 같이 본다.

| 관측 | 해석 |
|---|---|
| `hikaricp.connections.pending` > 0이 지속, `active` == `max`(10) | **Hikari pool 고갈** - 커넥션 자체를 못 받아 대기 중 |
| `bid_lock_failure`(=40909, `PessimisticLockingFailureException`) 카운트가 유의미하게 큼 | **MySQL row lock 대기/데드락** - 커넥션은 받았지만 `FOR UPDATE`에서 막힘 |
| `bid_system_failure` >> `bid_lock_failure`, 동시에 `pending`이 관측 기간 내내 높았음 | 그 차이(`system_failure - lock_failure`)는 대부분 **Hikari `connectionTimeout`(기본 30초) 초과**로 인한 `SQLTransientConnectionException` 계열 - `PessimisticLockingFailureException`이 아니라서 GlobalExceptionHandler catch-all(500)로 떨어진다 |
| `pending`이 거의 0으로 유지, `bid_lock_failure`만 늘어남 | pool은 여유 있고 순수하게 DB row lock 경쟁만 병목 |

즉 `bid_system_failure`와 `bid_lock_failure`의 차이, 그리고 그 시점의 `hikaricp.connections.
pending` 값을 같이 보면 두 원인을 구분할 수 있다 - 코드/설정 변경 없이 관측만으로 가능하다.

## 8. production 코드 변경 제안 (구현하지 않음)

1. `GlobalExceptionHandler.handlePessimisticLockingFailureException`에
   `CannotAcquireLockException` vs `DeadlockLoserDataAccessException` 구분 로그 1줄 - 현재
   둘 다 40909로 뭉개져 원인 구분이 안 됨.
2. `BidCommandService.placeManualBid`에 Micrometer `@Timed`(이미 actuator/micrometer
   의존성 존재, 새 라이브러리 불필요) - claim+critical section 전체 트랜잭션 시간을
   `/actuator/metrics`로 직접 관측 가능하게 함.
3. `spring.datasource.hikari.maximum-pool-size` 조정 여부 - 이번 baseline 실험 결과를 보고
   결정할 사안이며, 임의로 먼저 바꾸지 않았다.
