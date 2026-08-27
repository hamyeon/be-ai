# #34 No-lock Correctness 본 실험 — 실행 환경

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
| #34 실험 commit | (본 커밋 이후 별도 기록) | 실험 완료 후 커밋 시 확정 |

## No-lock 상태 재확인

실험 실행 직전 `backend/src/main` 전체에서 다음 검색 → 매치 없음(no-lock 상태 확인):

```text
@Version, @Lock, PESSIMISTIC_WRITE, PESSIMISTIC_READ, SELECT ... FOR UPDATE,
synchronized, Redisson/RLock, distributed lock
```

`Auction.java`는 baseline commit(`5bfe881`)에서 `@Version`/`getVersion()`이 제거된 상태 그대로다.
