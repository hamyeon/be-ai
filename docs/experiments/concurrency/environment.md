# Concurrency 실험 — 실행 환경 (#34/#35 Correctness / #36-A Performance)

이 문서는 #34 본 실험(20회)을 실제로 실행한 시점에 조회/확인한 값만 기록한다. 추정값은
없다 — 모두 harness의 실행 로그, `./gradlew dependencies`, `./gradlew javaToolchains`
출력 등 실제 조회 결과다.

## Frozen 조건과의 대조

harness(`ManualBidConcurrencyRaceIT#logEnvironment()`)가 매 실행 시 자동 조회한 값:

```text
[env] mysql.version=8.4.10 isolation=REPEATABLE-READ hikari.maximumPoolSize=20 springBootInstances=1
```

`docs/experiments/concurrency/protocol.md`의 §Frozen Main Experiment Conditions와 전부 일치.
discrepancy 없음.

## 상세 환경

| 항목 | 값 | 확인 방법 |
|---|---|---|
| MySQL version | `8.4.10` | Testcontainers `mysql:8.4` 컨테이너, harness가 `SELECT VERSION()`으로 실행 시 자동 조회 |
| Testcontainers image | `mysql:8.4` | `ManualBidConcurrencyRaceIT` `@Container` 선언 |
| Transaction isolation | `REPEATABLE-READ` | harness가 `SELECT @@transaction_isolation`으로 실행 시 자동 조회 |
| HikariCP maximumPoolSize | `20` | harness가 주입받은 `HikariDataSource.getMaximumPoolSize()`로 실행 시 자동 조회 |
| Spring Boot application instances | `1` | 전제(자동 조회 대상 아님), `@SpringBootTest(webEnvironment = NONE)` 단일 컨텍스트 |
| Worker count | `8` | `WorkloadConfig(8, 1000, 10000, 5000)` — frozen 조건 |
| Bidder count | `8` (1 thread = 1 bidder) | 위와 동일 |
| Test-only delay | `1000ms` | 위와 동일 |
| Initial price(startPrice) | `10000` | 위와 동일 |
| Bid increment | `5000` | 위와 동일 |
| JVM (Gradle toolchain) | Eclipse Temurin 17.0.18+8 | `./gradlew javaToolchains` — `build.gradle`의 `java.toolchain.languageVersion=17`로 고정 |
| mysql-connector-j | `9.7.0` | `./gradlew dependencies --configuration runtimeClasspath` |
| 실행 명령 | `./gradlew test --tests "com.vintic.backend.concurrency.ManualBidConcurrencyRaceIT.no_lock_상태에서_frozen_workload로_20회_본실험을_수행한다"` | 실제 실행에 사용한 명령 |
| Baseline tag | `exp/baseline-no-lock` | `git rev-list -n 1 exp/baseline-no-lock` |
| Baseline commit | `5bfe881e48f5400b3279c3d04b4191e427742381` | 위와 동일, baseline tag가 가리키는 commit과 일치 확인 |
| #34 branch | `experiment/#34-no-lock` | baseline commit을 조상으로 포함(`git merge-base --is-ancestor` 확인) |
| #34 실험 commit | `ea3828c` | `git log --oneline -1` |

## No-lock 상태 재확인 (#34)

실험 실행 직전 `backend/src/main` 전체에서 다음 검색 → 매치 없음(no-lock 상태 확인):

```text
@Version, @Lock, PESSIMISTIC_WRITE, PESSIMISTIC_READ, SELECT ... FOR UPDATE,
synchronized, Redisson/RLock, distributed lock
```

`Auction.java`는 baseline commit(`5bfe881`)에서 `@Version`/`getVersion()`이 제거된 상태 그대로다.

## #35 Pessimistic Lock 실행 환경

`experiment/#35-pessimistic-lock` 브랜치(HEAD `0f25900` + 아직 커밋되지 않은 작업 트리
변경 — §Git 상태는 완료 보고 참고)에서 harness가 실행 시 자동 조회한 값:

```text
[env] mysql.version=8.4.10 isolation=REPEATABLE-READ hikari.maximumPoolSize=20 springBootInstances=1
```

#34와 완전히 동일 — 독립변수(Pessimistic Write Lock 적용 여부) 외 환경은 변경되지 않았음을
실측으로 확인했다.

| 항목 | #34 No-lock | #35 Pessimistic Lock |
|---|---|---|
| MySQL version | 8.4.10 | 8.4.10 |
| Testcontainers image | `mysql:8.4` | `mysql:8.4` |
| Transaction isolation | REPEATABLE-READ | REPEATABLE-READ |
| HikariCP maximumPoolSize | 20 | 20 |
| Spring Boot application instances | 1 | 1 |
| Worker count | 8 | 8 |
| Bidder count | 8 | 8 |
| Test-only delay | 1000ms | 1000ms (동일 값, `findByIdForUpdate()` 반환 직후로 위치만 이동 — §protocol.md Pessimistic Lock Strategy) |
| Initial price(startPrice) | 10000 | 10000 |
| Bid increment | 5000 | 5000 |
| JVM (Gradle toolchain) | Temurin 17.0.18+8 | Temurin 17.0.18+8 (변경 없음) |
| mysql-connector-j | 9.7.0 | 9.7.0 (변경 없음, `./gradlew dependencies` 재확인) |
| 실행 명령 | `... ManualBidConcurrencyRaceIT.no_lock_상태에서_frozen_workload로_20회_본실험을_수행한다` | `./gradlew test --tests "com.vintic.backend.concurrency.ManualBidConcurrencyRaceIT.pessimistic_write_lock_상태에서_frozen_workload로_20회_본실험을_수행한다"` |
| 독립변수 | `@Version` 없음, application-level lock 없음 | `AuctionRepository.findByIdForUpdate()` + `@Lock(PESSIMISTIC_WRITE)` |
| Baseline tag/commit | `exp/baseline-no-lock` / `5bfe881` | 동일(변경 없음) — #35는 #34 결과 위에 production 변경만 추가 |
| 브랜치/커밋 | `experiment/#34-no-lock`, 병합 커밋 `0f25900`(main) | `experiment/#35-pessimistic-lock`, HEAD `0f25900` + 미커밋 작업 |

### Pessimistic Lock 상태 재확인 (#35)

구현 후 `backend/src/main` 전체에서 재검색:

```text
@Version                              → 없음 (baseline 이후 복구되지 않음)
PESSIMISTIC_WRITE / @Lock             → AuctionRepository.findByIdForUpdate() 1곳에만 존재
synchronized / Redisson / RLock       → 없음
retry / Retry                         → OpenAiVisionClient(AI vision 클라이언트, 입찰과 무관)에만 존재
```

독립변수가 정확히 1개(`PESSIMISTIC_WRITE` 적용 여부)로 제한됨을 확인했다.

### 실제 확인된 SQL

```text
Hibernate: select a1_0.id,a1_0.bid_increment,a1_0.created_at,a1_0.current_price,
a1_0.current_winner_id,a1_0.end_at,a1_0.product_id,a1_0.start_at,a1_0.start_price,
a1_0.status from auctions a1_0 where a1_0.id=? for update
```

`for update`가 실제 실행된 SQL에 포함됨을 확인했다(로그 원문, `pessimistic-experiment-run.log`
line 264 등).

## #36-A Performance 실행 환경

correctness(#34/#35)와 completely 별도인 성능 실험이다. 두 revision 모두 harness 실행 시
자동 조회한 `[perf-env]` 로그:

```text
No-lock:      [perf-env] mysql.version=8.4.10 isolation=REPEATABLE-READ hikari.maximumPoolSize=20 springBootInstances=1
Pessimistic:  [perf-env] mysql.version=8.4.10 isolation=REPEATABLE-READ hikari.maximumPoolSize=20 springBootInstances=1
```

| 항목 | No-lock (#36-A) | Pessimistic Lock (#36-A) |
|---|---|---|
| MySQL version | 8.4.10 | 8.4.10 |
| Testcontainers image | `mysql:8.4` | `mysql:8.4` |
| Transaction isolation | REPEATABLE-READ | REPEATABLE-READ |
| HikariCP maximumPoolSize | 20 | 20 |
| Spring Boot application instances | 1 | 1 |
| Concurrency | 8 | 8 |
| Warm-up batches | 5 (40 attempts, 폐기) | 5 (40 attempts, 폐기) |
| Measurement batches | 50 (400 attempts) | 50 (400 attempts) |
| Test-only delay | 0 (없음) | 0 (없음) |
| Initial price / Bid increment | 10000 / 5000 | 10000 / 5000 |
| JVM (Gradle toolchain) | Temurin 17.0.18+8 | Temurin 17.0.18+8 |
| SQL 콘솔 로깅 | `SPRING_JPA_SHOW_SQL=false` | `SPRING_JPA_SHOW_SQL=false` |
| 실행 위치 | `git worktree add --detach` → `exp/baseline-no-lock`(`5bfe881`), 별도 디렉터리 | 현재 브랜치(`chore/#36-concurrency-result-freeze`), production 코드가 `exp/pessimistic-lock`(`67cb4c7`)과 동일함을 `git diff`로 확인 |
| 실행 명령 | `CONCURRENCY_PERFORMANCE_LABEL=no-lock SPRING_JPA_SHOW_SQL=false ./gradlew --no-daemon test --tests "com.vintic.backend.concurrency.ManualBidPerformanceBenchmarkIT"` | `CONCURRENCY_PERFORMANCE_LABEL=pessimistic SPRING_JPA_SHOW_SQL=false ./gradlew --no-daemon test --tests "com.vintic.backend.concurrency.ManualBidPerformanceBenchmarkIT"` |
| Benchmark harness 동일성 | `ManualBidPerformanceBenchmarkIT.java`, 두 실행 위치에서 `diff` byte-for-byte 동일 확인 | 위와 동일 파일 |

CPU/OS: 두 측정 모두 동일 로컬 머신(Windows, 사용자 워크스테이션)에서 순차 실행 —
별도로 코어 수/모델을 조회해 기록하지는 않았다(같은 머신에서 두 revision을 순차 측정했다는
사실 자체가 "동일 환경"의 근거다. 다만 백그라운드 프로세스 등 머신 상태 자체의 완전한
동일성까지는 보장하지 않는다 — §protocol.md Performance Interpretation Rules 참고).

## #74 Optimistic Lock + Retry 실행 환경

`experiment/#74-optimistic-lock-retry` 브랜치에서 harness가 실행 시 자동 조회한 값:

```text
[opt-env]      mysql.version=8.4.10 isolation=REPEATABLE-READ hikari.maximumPoolSize=20 springBootInstances=1 maxAttempts=5 backoff=none
[opt-perf-env] mysql.version=8.4.10 isolation=REPEATABLE-READ hikari.maximumPoolSize=20 springBootInstances=1 maxAttempts=5 backoff=none
```

#34/#35와 완전히 동일 — 독립변수(Auction 최초 조회 방식: non-locking `findById` + bounded
retry) 외 환경은 변경되지 않았음을 실측으로 확인했다.

| 항목 | #34 No-lock | #35 Pessimistic Lock | #74 Optimistic Lock + Retry |
|---|---|---|---|
| MySQL version | 8.4.10 | 8.4.10 | 8.4.10 |
| Transaction isolation | REPEATABLE-READ | REPEATABLE-READ | REPEATABLE-READ |
| HikariCP maximumPoolSize | 20 | 20 | 20 |
| Worker/bidder count(correctness) | 8 | 8 | 8 |
| Correctness test-only delay | 1000ms (`findById` 대상) | 1000ms (`findByIdForUpdate` 대상) | 1000ms (`findById` 대상, armed 상태인 동안 매 attempt마다 적용) |
| Correctness runs | 20 | 20 | 20 |
| Performance concurrency | 8 | 8 | 8 |
| Performance warm-up batches | 5(폐기) | 5(폐기) | 5(폐기) |
| Performance measured batches/requests | 50 / 400 | 50 / 400 | 50 / 400 |
| Performance delay | 0 | 0 | 0 |
| Auction 최초 조회 | `findById`(non-locking) | `findByIdForUpdate`(`PESSIMISTIC_WRITE`) | `findById`(non-locking) + `@Version` |
| maxAttempts / maxRetries | 해당 없음 | 해당 없음 | 5 / 4 |
| backoff | 해당 없음 | 해당 없음 | none(즉시 재시도) |
| retry 대상 exception | 해당 없음 | 해당 없음 | `ObjectOptimisticLockingFailureException`만 |
| exhaustion 시 exception | 해당 없음 | 해당 없음 | `OptimisticRetryExhaustedException`(40909와 의미만 공유, 신규 매핑 추가 없음) |
| transaction boundary(claim~command) | claim+command 단일 물리 트랜잭션(`REQUIRED`) | 동일 | claim(T0, `REQUIRED`) 안에서 attempt마다 `REQUIRES_NEW`(T1..) 독립 커밋/롤백 |
| Correctness branch/tag | `experiment/#34-no-lock` | `experiment/#35-pessimistic-lock` | `experiment/#74-optimistic-lock-retry`, tag `exp/optimistic-lock-retry-correctness` |
| Performance revision | `exp/baseline-no-lock` worktree | 현재 브랜치(`exp/pessimistic-lock` 조상) | tag `exp/optimistic-lock-retry-performance` |

### #74 tag / commit hash

tag object hash(annotated tag 자체의 해시)와 그 tag가 가리키는 commit hash는 서로 다르다 —
혼동하지 않는다(`git rev-parse <tag>`는 tag object hash를, `git rev-list -n 1 <tag>`는 commit
hash를 반환한다).

| tag | tag object hash | 가리키는 commit hash | commit 요약 |
|---|---|---|---|
| `exp/optimistic-lock-retry-correctness` | `eb1e2d2767f37c66410e6ecb68a10a8b8d6defed` | `01007018fa73a6aea4d2477b5fc4946f254f387e` | `[experiment/#74] Optimistic correctness experiment harness` |
| `exp/optimistic-lock-retry-performance` | `8c230cb1c125a39587e42204ac634aa6b8bf2744` | `3a252db6fe945db4396cf24237d93f1291db8b15` | `[experiment/#74] Optimistic performance experiment harness` |

correctness raw(`optimistic-correctness.csv`, `raw/logs/optimistic-run-*.log`)는 correctness
tag commit(`0100701`) 다음 커밋 `884bc44`(`[experiment/#74] Optimistic correctness raw results`)
로 기록됐고, performance raw(`optimistic-performance.csv`)는 performance tag commit(`3a252db`)
다음 커밋 `ff716e6`(`[experiment/#74] Optimistic performance raw results`)로 기록됐다 — harness를
가리키는 tag commit과 raw 결과가 실제로 기록된 commit은 서로 다르다.

### #74 src/main 변경 고지

**"production code 완전 무변경"이 아니다.** `experiment/#74-optimistic-lock-retry` 브랜치는
`main` 대비 `backend/src/main`에 정확히 2개 파일, behavior-preserving 변경만 포함한다
(`git diff $(git merge-base main HEAD)..HEAD -- backend/src/main`으로 실측 확인, 그 외 파일
없음):

- `Auction.java`: `@Version private Long version` 필드 + getter 추가(총 13 lines). production
  Pessimistic 경로는 이 필드를 읽거나 조건으로 쓰지 않는다 — Hibernate가 매 UPDATE의 WHERE
  절에 version을 자동으로 추가할 뿐이다.
- `BidCommandService.java`: `findByIdForUpdate()` 이후 로직을 package-private
  `executeManualBidOnLoadedAuction()`으로 extract(총 19 lines diff). 실행 순서/validation/
  Proxy/종료연장/audit/예외 semantics는 무변경 — production `placeManualBid()`는 이 메서드를
  호출하는 것만 달라졌다.

이 2개 외에 `findByIdForUpdate`/`@Lock(PESSIMISTIC_WRITE)`는 `AuctionRepository`/
`BidCommandService`에 그대로 존재하며, Auction API endpoint/response/error contract, Auction
lifecycle scheduler(`AuctionStartScheduler`/`AuctionEndScheduler`), Order/BackupOffer
scheduler는 무변경이다. Redisson/Kafka/queue 추가 없음, `RaceWindowDelay`는
`backend/src/main`에 존재하지 않는다(전부 `backend/src/test`) — grep/diff로 실측 확인
(§#74-5 완료보고 참고).
