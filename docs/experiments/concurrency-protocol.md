# Concurrency Experiment Protocol

## 목적

수동 입찰(`POST /api/auctions/{auctionId}/bids`)에서 동일 `Auction`에 대한 동시 요청이
read-modify-write 경쟁을 일으킬 수 있는 조건을 재현 가능하게 고정하고, 이후 no-lock →
pessimistic lock(그리고 필요하면 다른 전략) 비교 실험이 **락 전략 외의 모든 조건을 동일하게**
유지한 채 이루어질 수 있도록 하는 것이 목적이다. 단발성으로 race 1회를 재현하는 것이 목표가
아니라, 반복 실행 가능한 harness와 고정된 workload/환경 조건을 만드는 것이 목표다.

## 비교 대상

- **no-lock baseline**: `experiment/no-lock` 브랜치, `Auction.@Version` 제거.
- **이후 적용할 concurrency-control strategy**: pessimistic lock(`SELECT ... FOR UPDATE`) 등,
  이후 별도 이슈/브랜치에서 진행. 이 문서의 "공통 환경"/"Workload" 절이 그 비교의 공통 기준선이 된다.

## 공통 환경

| 항목 | 값 | 확인 방법 |
|---|---|---|
| MySQL version | `8.4.10` (Testcontainers `mysql:8.4`) | harness가 매 실행 시 `SELECT VERSION()`으로 자동 조회해 로그에 출력 |
| Transaction isolation | `REPEATABLE-READ` (MySQL/InnoDB 기본값, 별도 설정 없음) | harness가 매 실행 시 `SELECT @@transaction_isolation`으로 자동 조회 |
| HikariCP maximumPoolSize | `20` (테스트 컨텍스트에서 `@DynamicPropertySource`로 고정) | harness가 주입받은 `DataSource`가 `HikariDataSource`면 `getMaximumPoolSize()`로 자동 조회해 로그에 출력 |
| Spring Boot application instance count | `1` (전제, 자동 조회 대상 아님) | 문서에만 기록 |
| Driver | `mysql-connector-j 9.7.0` | `./gradlew dependencies` 결과 |

HikariCP `maximumPoolSize=20`은 프로덕션 기본값(명시 설정 없음 → Spring Boot 기본 10)이 아니라
**이 실험 전용으로 고정한 값**이다. worker thread 수(파일럿 기준 최대 10 내외)가 커넥션 풀
부족으로 인위적으로 직렬화되는 것을 막기 위해 여유 있게 잡았다 — 이렇게 해야 관찰되는 경쟁이
"커넥션 풀 대기" 때문이 아니라 순수하게 `Auction` row에 대한 read-modify-write 경쟁이라고
말할 수 있다.

## Workload

- **thread count / bidder count**: `1 thread = 1 bidder = 1 bid request`. 값은 파일럿 단계에서
  조정(§Pilot Procedure), 확정되면 §Frozen Main Experiment Conditions에 기록.
- **bid amounts**: 모든 bidder가 같은 시작 상태(`currentPrice`, `bidIncrement`)를 기준으로
  `minAmount = currentPrice + bidIncrement`부터 `bidIncrement`씩 서로 다른 금액을 사용한다.
  race가 없다면 가장 큰 금액을 제출한 bidder가 항상 최종 승자여야 한다 — 그래야 결과 검증 시
  "정상적으로는 누가 이겨야 하는가"가 항상 명확하다.
- **idempotency key 정책**: 요청마다 `UUID.randomUUID()` 기반으로 완전히 서로 다른 키를 사용한다.
  이 실험은 Idempotency UNIQUE 경쟁(#32)이 아니라 `Auction` row 경쟁을 재현하는 것이 목적이므로,
  같은 key를 재사용해 idempotency conflict를 유발하지 않는다.

## Initial DB State

각 run 시작 시 새로 생성:
- `Auction`: `status=LIVE`, `currentPrice=startPrice`, `currentWinner=null`, `bidIncrement` 고정값
- 해당 `Auction`에 연결된 `Bid`: 0건
- 판매자 1명 + `workerCount`만큼의 서로 다른 bidder `User`(UUID 기반 email로 매 run마다 새로 생성)

## DB Reset Method

- Testcontainers `mysql:8.4` 컨테이너를 테스트 클래스 실행마다 새로 띄운다(완전히 빈 스키마에서 시작).
- 각 run(파일럿 반복)은 **이전 run과 다른 새 `Auction`/`Product`/`User` row**를 생성해서 사용한다
  (auto-increment PK라 이전 run의 데이터와 절대 겹치지 않음). 기존 데이터를 TRUNCATE/DELETE로
  지우는 방식이 아니라 "항상 새 대상"을 쓰는 방식으로 결정성을 확보했다 — 실행 순서에 의존하지 않는다.

## Synchronization Method

`CountDownLatch` 2단 구조:

```text
ready latch (workerCount) : 각 worker thread가 실행 준비(스레드 시작)를 마쳤음을 신호
start latch (1)            : 메인 스레드가 모든 worker의 ready를 확인한 뒤 한 번에 release
```

`ExecutorService`(고정 크기 = workerCount)에 전부 제출한 뒤 `ready.await()` → `start.countDown()`
순서로 최대한 동시에 시작시킨다. 기존 `ManualBidIdempotencyMySqlIT`(#32)의 2-thread 패턴을
N-thread로 일반화한 것이다.

## Test-only Delay

- **위치**: production `AuctionRepository` 빈을 감싼 test-only Mockito 대리 빈이 `findById(auctionId)`의
  **실제 조회 결과를 받은 직후**(반환 전)에 `Thread.sleep()`한다.
  `Auction 조회 → [여기] → 검증/상태변경(placeManualBid) → flush/commit` 구간에 정확히 대응한다.
- **적용 범위 제어**: `RaceWindowDelay`가 (1) 실험이 `arm()`되어 있고, (2) 조회 대상 `auctionId`가
  이번 run의 대상 auction과 일치할 때만 sleep한다. run이 끝나면 즉시 `disarm()`해서, 이후 결과
  검증을 위한 재조회(`findById`)에는 지연이 걸리지 않는다.
- **값**: `WorkloadConfig.delayMillis` — run마다 configurable, 하드코딩 아님.
- **production 코드 영향**: 0. `BidCommandService`를 포함한 어떤 main 소스도 수정하지 않았다.
  `AuctionRepository`의 `@Primary` 대체 빈은 해당 테스트의 `@TestConfiguration` 안에서만 존재한다.
- **barrier/latch를 쓰지 않은 이유**: 모든 worker가 "read 완료" 시점에 서로를 기다리는
  barrier 구조는 이론적으로 race window를 완벽히 동기화할 수 있지만, `worker thread 수 >
  HikariCP maximumPoolSize`인 경우 커넥션을 아직 획득하지 못한 thread 때문에 이미 커넥션을
  쥔 채 barrier에서 기다리는 다른 thread들이 영원히 풀리지 않는 connection starvation 구조가
  될 위험이 있다. 이번 baseline은 `CountDownLatch`로 요청 시작 시점만 동기화하고, 각 thread가
  독립적으로 sleep하는 단순한 방식을 우선 사용했다 — worker 수를 pool 크기보다 항상 작게
  유지하는 한 이 방식으로도 파일럿에서 경쟁을 재현하기에 충분했다(§Pilot Procedure 참고).
- **한계**: 이 delay는 운영 환경에서 실제 발생 확률이나 발생 빈도를 의미하지 않는다.
  순수하게 테스트 재현성을 위해 race window를 인위적으로 넓힌 것이다.

## Invariants

동시 실행 종료 후, 실제 DB를 재조회해서 다음을 검사한다(기존 6개 precondition 테스트를
대체하는 것이 아니라 별도의 post-state 검증이다 — §Invariants 처리 방식은 아래 참고):

1. **Auction price consistency**: 저장된 `Bid` 중 최고 금액과 `Auction.currentPrice`가 일치하는가.
2. **Winner consistency**: 최고 `Bid`의 bidder와 `Auction.currentWinner`가 일치하는가.
3. **Bid-Auction consistency (lost update)**: `Auction.currentPrice`보다 큰 금액의 `Bid`가
   존재하는가(존재하면 lost-update).
4. **Success/Bid count consistency**: 애플리케이션이 성공으로 보고한 요청 수와 실제 저장된
   `Bid` row 수가 일치하는가(성공 보고와 실제 영속 상태의 불일치를 잡기 위함).

### 기존 6개 invariant와의 관계

`BidCommandServiceTest`의 6개 규칙(`AuctionNotStartedException`, `AuctionClosedException`,
`SellerCannotBidException`, `AlreadyHighestBidderException`, `BidAmountTooLowException`,
`PenaltyRestrictedException`)은 **precondition/error-rule 테스트**이지 재사용 가능한 상태
checker가 아니라서, 이 문서의 post-state invariant를 위해 억지로 추출하지 않았다. 대신
기존 6개 테스트 + 수동 입찰/Idempotency 테스트를 그대로 regression suite로 유지하고, 이번
concurrency harness는 그 위에 "동시 실행 후 상태가 여전히 일관적인가"라는 별도 관점만 추가한다.

## Pilot Procedure

1. `setting/#33-concurrency-baseline`에서 harness 자체가 정상 동작하는지 확인
   (`Auction.@Version` 유지 상태, `ManualBidConcurrencyRaceIT`) — 완료.
2. `experiment/no-lock`에서 `Auction.@Version` 제거 후 동일 harness로 파일럿 실행 — 완료.
3. 1차 탐색은 한 번에 한 변수씩 escalation: `(workers=3,delay=200)` →
   `(3,500)` → `(8,500)` → `(8,1000)` → `(10,1000)`. `(8,1000)`에서만 위반 재현(1/1).
4. 재현성 확인을 위해 `(workers=8, delay=1000)`을 동일 조건으로 5회 반복 — 2/5 위반.
5. 조정 불가(고정)로 유지: 입찰 비즈니스 로직, transaction 구조, Idempotency 로직, DB isolation
   level, repository 구현, `Auction` 상태 변경 로직, invariant/assertion 기준.

## Pilot Results

### 1차 탐색 (한 변수씩 escalation)

| Pilot | Workers | Delay(ms) | Success | Failure | Persisted Bids | Actual Max Bid | Auction.currentPrice | Invariant Violation | 주요 결과 |
|---:|---:|---:|---:|---:|---:|---:|---:|---|---|
| 1 | 3 | 200 | 1 | 2 | 1 | 15000 | 15000 | No | 정상 — 1건만 성공, 나머지 deadlock |
| 2 | 3 | 500 | 1 | 2 | 1 | 25000 | 25000 | No | 정상 |
| 3 | 8 | 500 | 2 | 6 | 2 | 45000 | 45000 | No | 2건 성공, currentPrice가 실제 최고 Bid와 일치(위반 없음) |
| 4 | 8 | 1000 | 2 | 6 | 2 | 50000 | 30000 | **Yes** | PRICE_MISMATCH / WINNER_MISMATCH / LOST_UPDATE |
| 5 | 10 | 1000 | 1 | 9 | 1 | 25000 | 25000 | No | 정상 — 1건만 성공 |

### 재현성 확인 (workers=8, delay=1000ms 고정, 5회 반복)

| Pilot | Success | Failure | Persisted Bids | Actual Max Bid | Auction.currentPrice | Invariant Violation | 주요 결과 |
|---:|---:|---:|---:|---:|---:|---|---|
| 1 | 1 | 7 | 1 | 45000 | 45000 | No | 정상 |
| 2 | 1 | 7 | 1 | 45000 | 45000 | No | 정상 |
| 3 | 2 | 6 | 2 | 45000 | 20000 | **Yes** | PRICE_MISMATCH / WINNER_MISMATCH / LOST_UPDATE |
| 4 | 1 | 7 | 1 | 40000 | 40000 | No | 정상 |
| 5 | 3 | 5 | 3 | 35000 | 30000 | **Yes** | PRICE_MISMATCH / WINNER_MISMATCH / LOST_UPDATE |

**동일한 통제된 파일럿 조건(workers=8, delay=1000ms) 5회 중 2회에서 invariant violation 관찰.**
이 수치를 운영 환경의 race 발생 확률이나 no-lock failure rate로 표현하지 않는다 — §Limitations 참고.

`SUCCESS_COUNT_MISMATCH`(성공 보고 수와 persisted Bid 수 불일치)는 5회 전부 발생하지 않았다 —
deadlock으로 롤백된 트랜잭션의 Bid insert도 정확히 함께 롤백된다는 뜻이다(ACID 자체는 깨지지
않음). violation이 관찰된 두 run 모두, `@Version`이 제거되어 `UPDATE auctions ...`에
버전 조건이 없는 상태에서 여러 트랜잭션이 같은 stale 상태를 읽고 각자 커밋에 성공했고, 그
결과 `Auction.currentPrice`가 실제 persisted 최고 Bid보다 낮게 남았다 — **어느 트랜잭션이
실제로 몇 번째로 commit됐는지는 로그로 특정하지 않았으므로, "나중에 커밋한 트랜잭션이 이전
값을 덮어썼다"를 확정 사실로 서술하지 않는다.** 확인된 사실은 (1) stale read가 발생했고
(2) 버전 조건 없는 UPDATE라 lost update가 구조적으로 가능했으며 (3) 관찰된 최종 상태가 이
가능성과 부합한다는 것이다.

## Frozen Main Experiment Conditions

```text
MySQL version: 8.4.10 (Testcontainers mysql:8.4)
transaction isolation: REPEATABLE-READ
Spring Boot instances: 1
Hikari maximumPoolSize: 20 (실험 전용 고정값)

worker count: 8
bidder count: 8 (1 thread = 1 bidder)
delayMillis: 1000

initial auction price(startPrice): 10000
bidIncrement: 5000
bid amounts: 15000, 20000, 25000, 30000, 35000, 40000, 45000, 50000 (worker i → 15000 + i*5000)

DB reset method: run마다 새 Auction/Product/User row 생성(§DB Reset Method)
concurrency start mechanism: CountDownLatch(ready N + start 1) — §Synchronization Method

관찰 결과: 동일한 통제된 파일럿 조건 5회 중 2회에서 post-state invariant violation 관찰
(운영 환경 race 발생 확률이나 no-lock failure rate로 해석하지 않는다 — §Limitations 참고)
```

이 조건은 **frozen no-lock baseline으로 확정**되었다. worker/bidder 수, delay, bid amount,
initialPrice, bidIncrement, Hikari maximumPoolSize, MySQL version, isolation level, application
instance count, DB reset 방식, CountDownLatch 구조 — 전부 더 이상 변경하지 않는다. 이후
pessimistic lock 등 비교 실험은 이 표의 값을 그대로 사용한다.

## Baseline Git Reference

- Branch: `experiment/no-lock`
- Tag: `exp/baseline-no-lock`
- Baseline commit: `5bfe881e48f5400b3279c3d04b4191e427742381`

## Limitations

- **Test-only delay는 운영 환경에서의 실제 race 발생 확률을 측정하는 장치가 아니다.** 이 실험은
  race window를 의도적으로 확대해 correctness failure가 "가능한지"를 재현하는 실험이다.
- **"동일한 통제된 파일럿 조건 5회 중 2회에서 invariant violation 관찰"은 운영 환경의 실제
  race 발생 확률이나 no-lock failure rate가 아니다.** 백분율(%)이나 "재현율"로 환산해
  운영 발생 가능성처럼 표현하지 않는다. delay=1000ms는 운영 트래픽에서 절대 발생하지 않을
  인위적인 read-modify-write 창이며, 이 수치는 오직 "이 통제된 조건에서 harness가 위반을
  재현할 수 있는가"만을 의미한다.
- **MySQL/InnoDB 자체의 내부 lock은 여전히 존재한다.** 관찰된 `CannotAcquireLockException`
  (MySQL 1213 Deadlock)이 그 증거이며, no-lock 결과의 일부로 그대로 기록한다(삭제·무시하지
  않음) — 다만 이것은 **correctness violation과는 별도의 contention/failure 지표**다.
  실패한 트랜잭션은 Bid insert까지 포함해 전부 롤백되므로 그 자체가 데이터 정합성을 깨지는
  않는다. "no-lock"은 InnoDB 엔진 레벨 락까지 없앤다는 뜻이 아니라 application-level
  (`@Version`, `@Lock`, `SELECT FOR UPDATE`, 분산 락 등) concurrency-control strategy가
  없다는 의미다.
- 이 harness는 HTTP/Controller 계층을 거치지 않고 `ManualBidService`를 직접 호출한다 — Controller의
  헤더 파싱/인증 로직은 동시성 실험과 무관해서 제외했다(#32 `ManualBidIdempotencyMySqlIT`는 반대로
  HTTP 전 구간을 검증하는 것이 목적이라 방식이 다르다).
- Mock 인증(`MockUserRegistry`)이 고정된 3개 id(1,2,3)만 허용해서, HTTP 경유 시 bidder 수가
  2명으로 제한된다. 이 harness가 서비스 레이어를 직접 호출하는 이유 중 하나이기도 하다.
- Spring Boot instance count는 1로 전제했다 — 다중 인스턴스(로드밸런싱) 환경에서의 경쟁은
  이번 범위가 아니다.
- `setting/#33-concurrency-baseline` 단계의 파일럿 결과는 `@Version`이 살아있는 상태에서 나온
  것이라 no-lock 결과가 아니다 — harness 정상 동작 확인 용도로만 사용했다.
- correctness violation의 정확한 원인(어느 트랜잭션이 몇 번째로 commit됐는지)은 commit 순서를
  로그로 특정하지 않아 확정 사실로 서술하지 않는다 — §Pilot Results의 원인 서술 참고.
