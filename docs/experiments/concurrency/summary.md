# Concurrency Experiment Summary

이 문서는 서로 다른 두 실험을 다룬다 — **섞지 않는다.**

```text
Experiment A (Correctness, #34/#35)  → post-state invariant violation 여부
Experiment B (Performance, #36-A)    → correctness용 1000ms delay를 제거한 latency/throughput 비용
```

raw data는 각각 별도 CSV다. 이 문서는 그 raw CSV들을 그대로 재집계한 결과만 담는다 —
raw는 이 문서 작성 과정에서 수정하지 않았다.

```text
raw/no-lock-correctness.csv        (Experiment A, #34, 20 rows)
raw/pessimistic-correctness.csv    (Experiment A, #35, 20 rows)
raw/no-lock-performance.csv        (Experiment B, #36-A, 400 rows)
raw/pessimistic-performance.csv    (Experiment B, #36-A, 400 rows)
```

공통 조건: baseline tag `exp/baseline-no-lock`(`5bfe881`), pessimistic tag
`exp/pessimistic-lock`(`67cb4c7`), MySQL `8.4.10`, isolation `REPEATABLE-READ`, Hikari
maximumPoolSize `20`, concurrency `8`(1 thread = 1 bidder = 1 request), initial price
`10000`, bid increment `5000`. 상세는 [protocol.md](./protocol.md),
[environment.md](./environment.md) 참고.

---

## Experiment A — Correctness (#34/#35)

독립변수는 **Auction 최초 조회에 `PESSIMISTIC_WRITE`를 적용하는지 여부** 하나다.
test-only delay(1000ms)로 race window를 인위적으로 확대한 correctness stress workload다.

### No-lock (#34)

- runs: 20 / request attempts: 160
- Any post-state invariant violation: 3/20 (price/winner/lost update가 항상 같은 3개
  run(3, 4, 7)에서 함께 관찰됨)
- Success/persisted mismatch: 0/20
- Success requests: 25/160, Failure requests: 135/160
- 실패 135건 전부 `CannotAcquireLockException`(MySQL 1213), 다른 종류 예외 없음

### Pessimistic Lock (#35)

- runs: 20 / request attempts: 160
- Any post-state invariant violation: **0/20** (price mismatch/winner mismatch/lost
  update/success-persisted mismatch 전부 0/20)
- Success requests: 61/160, Failure requests: 99/160
- 실패 99건 전부 `BidAmountTooLowException`(business rejection), `CannotAcquireLockException`
  0/160, 그 외 예외 0/160

### Correctness Comparison

| Metric | No-lock (#34) | Pessimistic Lock (#35) |
| --- | ---: | ---: |
| Runs | 20 | 20 |
| Any invariant violation | 3/20 | 0/20 |
| Price mismatch | 3/20 | 0/20 |
| Winner mismatch | 3/20 | 0/20 |
| Lost update | 3/20 | 0/20 |
| Success/persisted mismatch | 0/20 | 0/20 |

| Metric | No-lock (#34) | Pessimistic Lock (#35) |
| --- | ---: | ---: |
| Attempts | 160 | 160 |
| Success | 25 | 61 |
| Business rejection | raw에 컬럼 없음(#34는 이 개념을 구분하지 않음) | 99 (전부 `BidAmountTooLowException`) |
| CannotAcquireLockException | 135 | 0 |
| Other exception | 0 | 0 |

`3/20`(run-level)과 `135/160`·`99/160`(request-level)은 서로 다른 지표이며 혼용하지 않는다.

### Representative No-lock Failure

- run: 3 (#34) — persisted max Bid 45000, `Auction.currentPrice` 15000, max Bid bidder 29,
  `Auction.currentWinner` 23, violations: `PRICE_MISMATCH`, `WINNER_MISMATCH`, `LOST_UPDATE`.
  다른 두 violation run(4, 7)은 `raw/no-lock-correctness.csv` + `raw/logs/no-lock-run-04.log`,
  `no-lock-run-07.log` 참고.

### Pessimistic Result

20개 run 전부 violation 없음. run 1: success=2, failure=6(전부 `BidAmountTooLowException`),
`Auction.currentPrice`=50000=max persisted Bid, `currentWinner`=12=max Bid bidder.

### Interpretation (Correctness)

**No-lock**: `@Version` 제거로 `UPDATE auctions ...`에 버전 조건이 없어, 여러 트랜잭션이
같은 stale `Auction` 상태를 읽고 각자 커밋에 성공할 수 있는 구조였다 — 관찰된 3개 violation
run의 최종 상태는 이 가능성과 부합한다. 어느 트랜잭션이 몇 번째로 commit됐는지는 로그로
특정하지 않았으므로 확정 사실로 서술하지 않는다.

**Pessimistic Lock**: `SELECT ... FOR UPDATE`가 Auction row 접근을 직렬화해 각 트랜잭션이
항상 직전에 commit된 최신 `currentPrice`를 기준으로 검증/수정하기 때문으로 해석된다 —
no-lock에서 관찰된 stale read 기반 lost update의 전제 조건 자체가 사라진 것과 일치한다.
동일한 통제된 조건의 20회 실험에서 No-lock은 3/20 run에서 violation이 관찰됐고, Pessimistic
Lock에서는 0/20으로 관찰되지 않았다 — 이 결과가 "Pessimistic Lock이 모든 환경에서 정합성을
완벽히 보장한다"를 의미하지는 않는다(§Limitations).

---

## Experiment B — Performance (#36-A)

correctness와 완전히 분리된 별도 실험이다. **test-only delay를 전혀 사용하지 않는다**
(`RaceWindowDelay` 미사용, sleep 없음).

### Measurement Protocol

- harness: `ManualBidPerformanceBenchmarkIT`(신규, correctness harness와 별개 파일).
  `AuctionRepository`를 감싸는 proxy가 전혀 없다 — production `ManualBidService`를 그대로
  호출해 두 revision에서 harness 소스가 byte-for-byte 동일할 수 있게 했다.
- concurrency: 8 (1 batch = 8 concurrent request attempts, correctness와 동일 개념)
- warm-up: 5 batches(40 attempts, raw에서 폐기) → measurement: 50 batches(400 attempts)
- latency 측정 경계: **service-level latency**(`ManualBidService.placeBid()` 호출 시작~반환).
  HTTP/Controller/JSON 직렬화/네트워크 비용은 포함하지 않는다 — "API latency"라고 부르지 않는다.
- throughput 측정 경계: batch별 `start` latch release 직전부터 해당 batch 8개 request
  전부 완료 직후까지의 wall-clock. DB reset/setup 시간은 제외.
- percentile 계산법: **nearest-rank**, `rank = ceil(percentile × N)`(1-indexed, 오름차순
  정렬). median/p95 모두 동일 방식.
- **revision isolation**: No-lock 성능은 `exp/baseline-no-lock`(`5bfe881`)을 `git worktree
  add --detach`로 별도 디렉터리에 체크아웃해 측정했다 — Pessimistic production 코드를
  Mockito 등으로 "락 없는 것처럼" 흉내 낸 결과가 아니라 실제 No-lock revision의 실제 코드다.
  Pessimistic 성능은 현재 브랜치(HEAD가 `exp/pessimistic-lock`(`67cb4c7`)의 후손이고 그
  시점 이후 production 변경 없음을 `git diff` 확인)에서 직접 측정했다. 두 실행 모두 동일한
  `ManualBidPerformanceBenchmarkIT.java`(byte-for-byte 동일, `diff` 확인)를 사용했다.
  출력 CSV 파일명만 `CONCURRENCY_PERFORMANCE_LABEL` 환경변수로 구분했다(no-lock/pessimistic).
- logging: 두 측정 모두 `SPRING_JPA_SHOW_SQL=false`로 Hibernate SQL 콘솔 로깅을 껐다
  (correctness 실험은 SQL 검증 목적상 켜져 있었음 — 성능 측정에서는 로깅 자체가 latency를
  왜곡하지 않도록 분리).

### No-lock (#36-A)

- attempts: 400 (success 52, `CONCURRENCY_DB_FAILURE` 348 — 전부 `CannotAcquireLockException`)
- overall latency: median 27.33ms, p95 38.36ms (N=400)
- success latency: median 29.25ms, p95 40.07ms (N=52)
- attempt throughput: 253.77/s, successful throughput: 32.99/s

### Pessimistic Lock (#36-A)

- attempts: 400 (success 144, business rejection 256 — 전부 `BidAmountTooLowException`,
  DB/concurrency failure 0)
- overall latency: median 60.23ms, p95 111.75ms (N=400)
- success latency: median 45.04ms, p95 99.42ms (N=144)
- business rejection latency: median 65.13ms, p95 120.03ms (N=256, 참고용)
- attempt throughput: 82.56/s, successful throughput: 29.72/s

### Performance Comparison

| Metric | No-lock | Pessimistic Lock |
| --- | ---: | ---: |
| Measurement attempts | 400 | 400 |
| Success | 52 | 144 |
| Business rejection | 0 (raw에 해당 outcome 없음) | 256 |
| DB/concurrency failure | 348 | 0 |
| Overall median | 27.33ms | 60.23ms |
| Overall p95 | 38.36ms | 111.75ms |
| Success median | 29.25ms | 45.04ms |
| Success p95 | 40.07ms | 99.42ms |
| Attempt throughput | 253.77/s | 82.56/s |
| Successful throughput | 32.99/s | 29.72/s |

### Interpretation (Performance)

동일한 로컬 경합 workload(concurrency=8, delay 없음)에서 두 concurrency-control strategy를
적용했을 때 관찰된 end-to-end service-level latency/throughput 차이는 다음과 같다.

- overall p95는 No-lock 38.36ms → Pessimistic 111.75ms로 증가했다. 하지만 이 차이를
  "Pessimistic Lock 자체의 순수 overhead"로 단정하지 않는다 — 두 전략의 outcome mix가
  근본적으로 다르기 때문이다(No-lock은 실패 348건이 전부 짧은 DB 예외, Pessimistic은 실패
  256건이 row lock 대기 후의 business rejection).
- attempt throughput은 253.77/s → 82.56/s로 크게 줄었다. No-lock에서는 실패(DB 예외)가
  빠르게 끝나 "시도" 자체는 더 많이 처리되지만, 그중 대부분(348/400)이 성공적인 입찰로
  이어지지 않는다.
- successful throughput은 32.99/s → 29.72/s로, attempt throughput만큼 크게 벌어지지 않았다
  — "실제로 성공한 입찰"만 놓고 보면 두 전략의 처리 속도 차이가 attempt throughput 차이보다
  작다는 뜻이다. No-lock의 높은 attempt throughput은 상당 부분 실패로 끝나는 시도에서
  나온다.
- Pessimistic Lock은 row 접근을 직렬화하므로, 뒤에 lock을 얻은 요청이 이미 최신
  `currentPrice`를 보고 `BidAmountTooLowException`으로 정상 거부되는 경우(256/400)가
  No-lock의 DB 예외(348/400) 자리를 대체한 것으로 해석된다.

---

## Decision

- Correctness 관점: 동일 frozen workload 20회에서 No-lock은 3/20 run에서 post-state
  invariant violation을 보였고, Pessimistic Lock은 0/20이었다.
- Performance 관점: 동일 로컬 workload 400 attempts에서 Pessimistic Lock은 No-lock 대비
  overall/success 양쪽 모두 median·p95가 높고 attempt throughput이 낮았다. 다만 successful
  throughput 차이는 상대적으로 작았다.
- 이 두 관점을 종합해 "이 read-modify-write 경로에 대해 correctness를 얻는 대신 어느 정도의
  latency/throughput 비용을 지불할지"는 이 문서가 결정하지 않는다 — 실험 결과를 raw 그대로
  보존하고 사실을 사실대로, 해석은 해석대로 분리해서 남기는 것이 이 문서의 목적이다.

## Alternatives Considered (#40)

이 문서의 실험(#34/#35/#36)은 No-lock과 Pessimistic Lock(`SELECT ... FOR UPDATE`) 둘만
비교했다. 아래는 다른 concurrency-control 대안들을 왜 이번 범위에서 실험조차 하지
않았는지에 대한 정책적 사유다 — 실험 데이터가 아니라 설계 판단이므로 위 §Decision과
섞지 않는다.

**Idempotency vs cross-user concurrency는 서로 대체 관계가 아니다.**

```
Idempotency        — 동일 user / 동일 logical request retry의 중복 처리 방지
Concurrency control — 서로 다른 user/request가 동일 Auction state를 동시에 RMW하는 문제 해결
```

`IdempotencyClaimService`가 있다고 해서 pessimistic lock이 불필요해지지 않는다 — 전자는
"같은 사람이 같은 요청을 두 번 보냈는가"를, 후자는 "다른 사람들이 동시에 같은 row를
고쳤는가"를 다루는 별개의 문제다.

| 대안 | 채택/제외 | 사유 |
| --- | --- | --- |
| synchronized / ReentrantLock | 제외 | JVM local lock이라 multi-instance에서 공유되지 않는다. authoritative state는 MySQL row이고, JVM 메모리 락은 DB transaction과 직접 결합되지 않는다. 현재 single-instance 배포에서 기술적으로는 가능하지만, 확장성과 transaction consistency 기준으로 채택하지 않는다 |
| Atomic UPDATE (단일 conditional UPDATE) | 제외 | 단순 counter 증가가 아니라 `currentPrice`/`currentWinner` 갱신, validation, `Bid` 생성, Idempotency 처리, 향후 Proxy resolution까지 하나의 트랜잭션으로 일관돼야 한다. 이 전체를 단일 conditional UPDATE 하나로 표현하는 것은 부적절하다 |
| SERIALIZABLE isolation | 제외 | 트랜잭션 전체의 isolation을 강화하는 방식이라 영향 범위와 contention 비용이 이 read-modify-write 경로(경쟁 대상이 단일 Auction row로 명확함)에 비해 과하다 |
| Pessimistic Lock (`SELECT ... FOR UPDATE`) | **채택** | 단일 MySQL Auction row가 contention point이고 authoritative read부터 직렬화가 필요하다. #35 frozen correctness workload에서 0/20 post-state violation 관찰(§Experiment A) — 단, 이것이 절대적 무결성 확률 0을 의미하지는 않는다(§Limitations). #36에서 latency/tail-latency 비용도 함께 확인했다(§Experiment B) |
| Optimistic Lock + retry | 후속 후보 | 충돌률이 낮은 workload라면 장점이 있으나, retry 횟수/backoff/재검증/최종 실패 semantics를 추가로 설계해야 한다. 이번 범위에서 실험하지 않았다 |
| Redisson (분산 락) | 보류 | 단일 MySQL row 문제에 Redis라는 별도 coordination 시스템을 추가로 들일 필요가 없다. 다중 DB 또는 DB transaction 바깥 resource까지 묶는 distributed coordination 요구가 생기면 재검토한다 |
| Queue / Kafka (event-driven serialize) | 보류 | 현재 synchronous bid response 계약과 맞지 않고 운영 복잡도가 과하다. 고부하에서 입찰을 완전히 serialize하는 event-driven architecture가 필요해질 때 별도 검토한다 |

## Limitations

### Correctness

- **이 결과를 운영 환경의 race 발생 확률/실패율로 해석하지 않는다.** `delay=1000ms`는
  운영 트래픽에서 절대 발생하지 않는 인위적으로 확대된 read-modify-write 창이다.
- 통제된 correctness stress workload의 160개 request attempt 중 no-lock은 135개가
  `CannotAcquireLockException`으로, pessimistic lock은 99개가 `BidAmountTooLowException`
  으로 종료됐다 — 이 값들을 "no-lock/pessimistic failure rate"나 운영 요청 실패율로
  표현하지 않는다.
- **Pessimistic Lock의 `0/20`은 "모든 환경에서 정합성을 완벽히 보장한다"를 의미하지 않는다.**
  single application instance, single MySQL, single Auction row contention, frozen test
  workload라는 이번 검증 범위 안에서의 관찰값이다.
- Pessimistic Lock의 두 트랜잭션 간 실제 블로킹은 별도의 격리된 lock-wait 테스트가 아니라
  #35 20회 본 실험 자체의 결과(0 invariant violation, `CannotAcquireLockException` 0/160,
  모든 run이 타임아웃 없이 정상 종료)로부터 추론했다.
- 트랜잭션 rollback 시 lock이 실제로 해제되는 시점을 로그로 직접 관찰하지는 않았다.
  InnoDB row lock이 transaction-scoped라 commit뿐 아니라 rollback 시에도 해제되는 구조라는
  설명과, 이번 실험에서 lock leak이 관찰되지 않았다는 사실을 구분해서 서술한다(§protocol.md
  Pessimistic Lock Strategy).

### Performance

- **local 단일 인스턴스 결과를 production latency/throughput으로 일반화하지 않는다.**
  단일 애플리케이션 인스턴스, 단일 MySQL, 로컬 머신 CPU/스토리지 조건에서의 관찰값이다.
- 이 harness는 `ManualBidService`를 직접 호출한 **service-level(application-to-DB)
  latency**다 — HTTP Controller, JSON 직렬화, 네트워크 왕복 비용은 포함하지 않는다.
- outcome mix가 전략마다 다르므로 overall median/p95만으로 "그 전략의 순수 오버헤드"를
  단정하지 않는다(§Interpretation (Performance)).
- warm-up 5 batch는 raw에 포함하지 않았다 — JIT/커넥션 풀/Hibernate 초기화 효과를
  측정값에서 배제하기 위함이다.
- correctness(#34/#35)의 elapsed time/latency는 test-only delay(1000ms)를 포함하고 있어
  이번 성능 결과와 비교 대상이 아니다 — 서로 다른 실험이다.
- No-lock과 Pessimistic Lock의 입찰 read-modify-write 경로에는 lock 전략 외 의미 있는
  production 차이가 없음을 diff로 확인했지만, 두 측정 대상의 repository 전체 revision
  자체는 동일하지 않으므로 완전한 microbenchmark 수준의 단일 변수 비교로 일반화하지
  않는다.

## Remaining Questions

- No-lock에서 3개 violation run 모두 success=2였지만, success=2인 다른 run(2, 10)은
  violation이 없었다 — success count만으로는 violation 여부를 예측할 수 없었다.
- Pessimistic Lock에서 request-level success가 25→61로 늘어난 것(correctness)과 관련해,
  정확한 lock 대기열 순서는 로그로 추적하지 않았다 — 필요하면 MySQL `performance_schema`/
  `information_schema.INNODB_LOCK_WAITS` 기반의 별도 관찰이 필요하다(이번 범위 밖).
- Performance 결과에서 Pessimistic Lock의 p95(111.75ms)가 median(60.23ms)의 약 1.85배로
  No-lock(38.36ms/27.33ms, 약 1.40배)보다 tail이 상대적으로 더 무겁다 — 이것이 lock 대기열
  길이 증가 때문인지, 특정 batch에서의 우연한 지연 때문인지는 이번 raw(request-level)로는
  batch별 대기열 위치까지 특정하지 않아 확정하지 않는다.
