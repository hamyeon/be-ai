# Concurrency Correctness 실험 — 실행 환경 (#34 No-lock / #35 Pessimistic Lock)

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
