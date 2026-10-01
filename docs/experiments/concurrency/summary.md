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

## Experiment C — Optimistic Lock + Retry (#74)

독립변수는 이전과 동일하게 **Auction 최초 조회 방식** 하나이며, 이번 값은 "non-locking
`findById` + `@Version` conflict 시 bounded retry(`maxAttempts=5`, backoff 없음)"다. raw는
`raw/optimistic-correctness.csv`(20 rows) + `raw/optimistic-performance.csv`(400 rows). 상세
설계는 [protocol.md §Optimistic Lock + Retry Experiment](./protocol.md)와
[environment.md §74](./environment.md) 참고.

### Optimistic Correctness (#74-3)

- runs: 20 / request attempts: 160
- Any post-state invariant violation: **0/20**
- Success requests: 20/160, business rejection: 1/160, unexpected DB failure: 139/160(전부
  `CannotAcquireLockException`)
- optimistic conflict(`ObjectOptimisticLockingFailureException`): **1건**, total retries: 1건,
  average retries/request: 0.00625(1/160), retry distribution: {0회: 159, 1회: 1}, exhaustion:
  0건

**"Optimistic conflict가 거의 발생하지 않았다"라고 단순 해석하지 않는다.** 정확히는:
Auction 선행 Pessimistic serialization이 사라지면서(No-lock/Optimistic 둘 다 Auction 최초
조회가 non-locking), 여러 request가 `executeManualBidOnLoadedAuction()` 내부
`cancelOwnActiveAutoBidIfPresent()`가 쓰는 `AutoBidSetting.findCurrentByAuctionIdAndUserIdForUpdate()`
(`PESSIMISTIC_WRITE`, 이 workload엔 매칭 row가 없는 조회)에 동시 진입해 InnoDB gap-lock
경쟁으로 먼저 탈락했다 — 이는 No-lock(#34)에서도 이미 관찰된 것과 같은 종류의 DB lock
failure다(§Limitations, No-lock 135/160 `CannotAcquireLockException`). Auction 버전 충돌
지점에 도달하기도 전에 대부분의 request가 이 downstream lock에서 먼저 종료되어, optimistic
retry mechanism 자체는 이번 workload에서 충분히 관찰되지 못했다. 유일하게 관찰된 conflict
(run 15, bidder 132)는 §Retry Semantics에서 그대로 인용한다.

### Optimistic Performance (#74-4B)

- attempts: 400 (success 50, `UNEXPECTED_DB_FAILURE` 350 — 전부 `CannotAcquireLockException`,
  business rejection 0)
- overall latency: median 34.38ms, p95 50.26ms (N=400)
- attempt throughput: 196.99/s, successful throughput: 24.62/s
- optimistic conflict: **0건**, total retries: 0건, exhaustion: 0건
- no-retry latency: median 34.38ms, p95 50.26ms (N=400) — retried latency: **표본 0건, 계산
  불가.** delay=0인 조건에서는 Auction row에 대한 실제 read-modify-write 겹침 자체가 매우
  좁아, retry가 발생하기도 전에(또는 발생 없이) 대부분 downstream AutoBidSetting lock에서
  종료됐다.

### 3전략 비교 — Correctness

| Metric | No-lock (#34) | Pessimistic Lock (#35) | Optimistic + Retry (#74) |
| --- | ---: | ---: | ---: |
| Runs | 20 | 20 | 20 |
| Logical requests | 160 | 160 | 160 |
| Any invariant violation | 3/20 | 0/20 | 0/20 |
| Success | 25 | 61 | 20 |
| Business rejection | 해당 컬럼 없음 | 99 | 1 |
| Unexpected/DB failure | 135 (`CannotAcquireLockException`) | 0 | 139 (`CannotAcquireLockException`) |
| Lost update / winner mismatch | 3/20 각각 | 0/20 각각 | 0/20 각각 |
| Duplicate / partial-state Bid | 관찰 안 함(해당 개념 없음) | 관찰 안 함 | **0건**(success=persisted Bid 합 20=20, 전 run `successPersistedMismatch=false`) |
| Optimistic conflicts | 해당 없음 | 해당 없음 | 1 |
| Total retries | 해당 없음 | 해당 없음 | 1 |
| Avg retries/request | 해당 없음 | 해당 없음 | 0.00625 |
| Exhausted | 해당 없음 | 해당 없음 | 0 |

### 3전략 비교 — Performance

| Metric | No-lock (#36-A) | Pessimistic Lock (#36-A) | Optimistic + Retry (#74-4B) |
| --- | ---: | ---: | ---: |
| Measurement attempts | 400 | 400 | 400 |
| Overall median | 27.33ms | 60.23ms | 34.38ms |
| Overall p95 | 38.36ms | 111.75ms | 50.26ms |
| Attempt throughput | 253.77/s | 82.56/s | 196.99/s |
| Successful throughput | 32.99/s | 29.72/s | 24.62/s |
| Success | 52 | 144 | 50 |
| Business rejection | 0 | 256 | 0 |
| DB failure | 348 | 0 | 350 |
| Optimistic conflicts | 해당 없음 | 해당 없음 | 0 |
| Total retries | 해당 없음 | 해당 없음 | 0 |
| Exhausted | 해당 없음 | 해당 없음 | 0 |
| No-retry latency (median/p95) | 해당 없음 | 해당 없음 | 34.38ms / 50.26ms (N=400) |
| Retried latency (median/p95) | 해당 없음 | 해당 없음 | 표본 0건 |

**outcome mix가 세 전략 모두 다르므로 attempt throughput만으로 우열을 판단하지 않는다.**
No-lock과 Optimistic은 outcome mix(대부분 `CannotAcquireLockException`으로 빠르게 실패)가
서로 비슷해 attempt throughput이 둘 다 높게(253.77/s, 196.99/s) 나온다 — 이는 "실패가 빨라서
많은 시도를 처리한 것"이지 "그 전략이 더 우수해서"가 아니다. Pessimistic은 실패(business
rejection)도 lock 대기를 거치므로 상대적으로 느리지만(median 60.23ms) DB failure가 전혀
없다. Optimistic은 이번 workload에서 conflict/retry 비용이 사실상 0으로 관찰돼, latency
차이(34.38ms vs No-lock 27.33ms)는 retry 비용이 아니라 `@Version` 컬럼 추가/`OptimisticBidRetryOrchestrator`
+`OptimisticBidAttemptService`+Idempotency claim 경유에 따른 부가적인 서비스 계층 오버헤드일
가능성이 높다 — 이번 raw만으로는 그 오버헤드의 정확한 출처(추가 bean 호출 vs `@Version`
컬럼)를 분리하지 않는다.

### Retry Semantics: retry ≠ 이전 validation 재실행

correctness run 15에서 실제로 관찰된 유일한 conflict 사례(`raw/logs/optimistic-run-15.log`):

```text
bidder=132 amount=20000
  attempt 1: Auction.currentPrice=10000(읽은 시점) 기준으로 20000은 유효(minNext=15000) →
             commit 시도 → 다른 bidder(136)가 먼저 currentPrice=40000으로 commit →
             ObjectOptimisticLockingFailureException(conflict 1회)
  attempt 2: 새 트랜잭션에서 Auction을 다시 조회 → currentPrice=40000(최신) →
             minNextBidAmount=45000 → amount(20000) < 45000 → BidAmountTooLowException
결과: exhaustion이 아니라 정상 business rejection(§protocol.md §6과 일치)
```

이 사례가 보여주는 것은 **retry가 "이전 attempt의 validation을 그대로 다시 실행"하는 것이
아니라는** 점이다. attempt 2는 attempt 1의 계산값(어떤 것도)을 재사용하지 않고,
`OptimisticBidAttemptService.attempt()` → `findById()`(새 트랜잭션, 최신 커밋 상태) →
`BidCommandService.executeManualBidOnLoadedAuction()`(상태/판매자/최고입찰자/최소금액/정렬
검증 전체)을 처음부터 다시 수행한다. 즉:

```text
retry ≠ 이전 validation 재실행(캐시된 계산값으로 재시도)
retry = 최신 state 기준으로 command 전체를 처음부터 재검증
```

그 결과 attempt 1에서는 유효했던 금액이 attempt 2에서는 무효가 될 수 있고, 이는 버그가
아니라 "최신 상태 기준 재검증"이 정확히 의도대로 동작한 것이다.

### Idempotency와 Internal Retry의 관계

두 가지를 명확히 구분한다:

```text
HTTP duplicate request  → 기존 Idempotency-Key / claim UNIQUE 제약 / exact snapshot replay가 담당
Internal optimistic retry → 하나의 logical command 안에서 벌어지는 attempt 단위 transaction retry
```

`OptimisticManualBidService`는 production `ManualBidService`와 동일한 얇은 진입점 +
`IdempotencyClaimService.claimAndExecute()` 위임 구조를 그대로 재사용한다(ad-hoc 두 번째
idempotency 시스템 없음). claim insert + 최종 response snapshot 커밋은 `claimAndExecute()`의
단일 물리 트랜잭션(T0) 하나에서 이루어지고, 그 안에서 호출되는
`OptimisticBidRetryOrchestrator`(non-tx)와 `OptimisticBidAttemptService.attempt()`
(`REQUIRES_NEW`)는 T0를 suspend한 채 매 attempt마다 독립적으로 커밋/롤백된다.

**정상 실행에서 실측 확인한 것**(#74-2, 실제 MySQL IT 6/6 통과):
- duplicate Bid 없음(성공 1건당 persisted Bid 정확히 1건)
- 실패(conflict/business rejection/exhaustion) attempt는 Auction/Bid mutation까지 포함해
  전부 rollback — partial state 없음
- same Idempotency-Key + same payload → 재실행 없이 원본 결과(attempt/conflict count 포함)
  그대로 replay
- same key + different payload → `IdempotencyPayloadMismatchException`(40905), 재실행 없음
- exhaustion 시 claim도 함께 rollback되어 같은 key로 이후 재시도가 claim 잔여물과 모순 없이
  정상 동작

**한계(Idempotency crash window) — 반드시 함께 기록한다.** 성공한 attempt의 Auction/Bid
변경은 `REQUIRES_NEW`이므로 T0(claim) 커밋보다 **먼저** 독립적으로 커밋된다. 따라서
"Bid는 이미 커밋됐는데 claim/snapshot 커밋 직전에 프로세스가 죽는" crash window가 이론상
존재한다 — 이 경우 같은 key로 재시도하면 claim row가 없어(rollback되지 않고 애초에
아직 없는 상태) 커맨드가 다시 실행되어 중복 Bid가 발생할 수 있다. **production Pessimistic
경로는 command와 claim/snapshot이 하나의 물리 트랜잭션이라 이 window가 존재하지 않는다.**
이 실험은 정상 종료(성공/business rejection/exhaustion) 케이스만 실제 MySQL로 검증했고,
프로세스 crash 자체를 재현하지 않았다 — 이 optimistic 실험 경로를 **"완전한
production-ready optimistic strategy"라고 표현하지 않는다.**

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
- **Optimistic Lock + Retry(#74) 추가 후**: correctness 관점에서 Optimistic도 0/20으로
  invariant violation이 없었다(Pessimistic과 동일). 다만 이번 workload에서는 대부분의
  request가 Auction 버전 충돌 지점 이전에 downstream `AutoBidSetting` lock 경쟁으로
  탈락해(§Experiment C), optimistic conflict/retry 자체는 160개 중 1건만 관찰됐다 — 이
  결과만으로 Optimistic Lock + Retry의 correctness나 retry 비용을 결론짓지 않는다(표본
  부족, §Experiment C Limitations). Performance 관점에서 Optimistic(median 34.38ms, attempt
  TPS 196.99/s)은 No-lock과 Pessimistic 사이에 위치했지만, 이 역시 conflict 0건인 상태의
  측정값이라 "retry 비용을 포함한 성능"으로 해석하지 않는다.

## Production Strategy Reconsideration (#74)

이번 실험값과 §Experiment C의 Idempotency crash window 한계를 근거로 판단한다 — 이 branch
에서 실제 production lock 전략은 교체하지 않았다.

**결론: 현재 production Pessimistic Lock(`findByIdForUpdate` + `PESSIMISTIC_WRITE`)을
유지한다.**

- **Optimistic으로 즉시 교체하지 않는 이유**:
  1. 이번 측정 workload에서 optimistic conflict가 correctness 160건 중 1건, performance
     400건 중 0건만 관찰돼, retry mechanism의 correctness/성능 비용을 통계적으로 신뢰성
     있게 평가할 표본이 없다(§Experiment C).
  2. Idempotency claim(T0)과 optimistic attempt 커밋(T1..REQUIRES_NEW)이 서로 다른 물리
     트랜잭션이라 발생하는 crash window(§Experiment C)가 존재를 확인했고, 이를 닫는 별도
     설계(예: claim/커맨드를 다시 한 트랜잭션으로 묶거나 crash 복구 로직 추가)가 아직
     없다 — production Pessimistic 경로는 이 window 자체가 없다.
  3. Pessimistic Lock은 이미 #35/#36-A에서 0/20 invariant violation + `CannotAcquireLockException`
     0/160(correctness), DB failure 0/400(performance)로 이 route의 downstream
     `AutoBidSetting` lock 경쟁까지 포함해 가장 예측 가능한 결과를 보였다(Auction을 먼저
     직렬화해 그 이후 lock 경합 자체가 발생하지 않음).
- **추가 검증이 필요한 부분**: (a) 실제로 conflict가 자주 발생하는 workload(예: Auction
  선행 직렬화를 인위적으로 제거하지 않고도 진짜 hot auction에서 자연 발생하는 동시 입찰
  빈도)에서의 재측정, (b) claim/attempt 트랜잭션 경계를 하나로 합치거나 crash 복구를 위한
  idempotent replay 전략 설계, (c) `AutoBidSetting` FOR UPDATE 자체의 lock topology 재검토
  (이번 실험 범위 밖, §Experiment C에서 도입 원인만 확인했고 수정하지 않음).
- 이 결론은 **이번 측정 workload(로컬 단일 인스턴스, frozen dataset, 특정 delay 조건)
  범위 안에서만** 유효하다 — "Optimistic은 항상 느리다/불안전하다"거나 "Pessimistic은
  항상 안전하다"를 의미하지 않는다.

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
| Optimistic Lock + retry | **실험 완료(#74), production 미채택** | correctness 0/20 invariant violation은 Pessimistic과 동일했으나, 이번 workload에서 conflict/retry 표본이 극히 적어(correctness 1/160, performance 0/400) retry 비용을 신뢰성 있게 평가할 수 없었다. 또한 Idempotency claim과 attempt 커밋이 분리된 트랜잭션이라 발생하는 crash window(§Experiment C)가 아직 해소되지 않았다. 상세는 §Experiment C, §Production Strategy Reconsideration(#74) 참고 |
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

### Optimistic Lock + Retry (#74)

- **표본 부족**: correctness 160개 request 중 optimistic conflict는 1건, performance
  400개 request 중 0건만 관찰됐다 — retry 발생 시 latency(retried latency)는 correctness
  1건, performance 0건으로 어떤 통계적 결론도 내리지 않는다. 이 값들을 "optimistic retry는
  비용이 낮다/없다"로 일반화하지 않는다 — 단지 이번 workload에서 conflict가 거의 발생하지
  않았을 뿐이다.
- **표본이 부족한 원인 자체가 결과다**: Auction 선행 Pessimistic serialization이 없으면
  (No-lock/Optimistic 공통) 여러 request가 `AutoBidSetting.findCurrentByAuctionIdAndUserIdForUpdate()`
  (`PESSIMISTIC_WRITE`, 매칭 row 없는 조회)에 동시 진입해 InnoDB gap-lock 경쟁으로 먼저
  탈락한다 — No-lock(#34, 135/160)과 Optimistic(#74, correctness 139/160·performance
  350/400) 모두에서 이 downstream lock failure가 Auction 버전 충돌보다 압도적으로 많이
  관찰됐다. 이는 새로 발견된 결함이 아니라 두 전략이 Auction 최초 조회를 non-locking으로
  둔다는 공통점에서 구조적으로 노출되는 현상이다.
- **Idempotency crash window**: claim(T0)과 성공한 attempt의 커밋(T1..`REQUIRES_NEW`)이
  서로 다른 물리 트랜잭션이라, T1 커밋 이후 T0 커밋 이전 사이에 프로세스가 죽으면 Bid는
  남고 claim/snapshot은 없는 상태가 될 수 있다 — 같은 key로 재시도하면 중복 Bid로 이어질
  수 있다. 이 실험은 정상 종료 케이스만 실제 MySQL로 검증했고 crash를 재현하지 않았다.
  **이 optimistic 실험 경로를 "완전한 production-ready optimistic strategy"라고 표현하지
  않는다.**
- **Auction.@Version/BidCommandService extract는 production code 변경이다.** "production
  code 완전 무변경"으로 서술하지 않는다 — behavior-preserving임을 diff로 확인했을 뿐이다
  (§environment.md #74 src/main 변경 고지).
- delay=1000ms가 armed 상태인 동안 매 attempt(재시도 포함)에 적용되도록 구성해, No-lock/
  Pessimistic과 동일한 "test-only race window 위치/값"을 재사용했다 — 다만 이로 인해 retry가
  있는 이 전략에서는 delay가 여러 번 누적 적용될 수 있다는 점이 No-lock/Pessimistic과의
  구조적 차이다(§protocol.md Optimistic Lock + Retry Experiment).

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
