# Concurrency Correctness Experiment Summary

`docs/experiments/concurrency/raw/no-lock-correctness.csv`(#34, 20 rows)와
`raw/pessimistic-correctness.csv`(#35, 20 rows)를 각각 raw data 그대로 재집계한 결과다.
두 raw CSV 모두 이 문서 작성 과정에서 수정하지 않았다.

## Conditions (공통)

- baseline tag: `exp/baseline-no-lock`
- baseline commit: `5bfe881e48f5400b3279c3d04b4191e427742381`
- MySQL: `8.4.10` (Testcontainers `mysql:8.4`)
- isolation: `REPEATABLE-READ`
- Hikari maximumPoolSize: `20`
- workers/bidders: `8` / `8` (1 thread = 1 bidder = 1 request)
- delay: `1000ms` (test-only, Auction 최초 authoritative read 반환 직후)
- initial price: `10000`
- bid increment: `5000`

두 실험의 독립변수는 오직 **Auction 최초 조회에 `PESSIMISTIC_WRITE`를 적용하는지 여부**
하나다. 상세는 [protocol.md](./protocol.md), [environment.md](./environment.md) 참고.

## No-lock (#34)

- runs: 20 / request attempts: 160
- Any post-state invariant violation: 3/20 (price/winner/lost update가 항상 같은 3개
  run(3, 4, 7)에서 함께 관찰됨)
- Success/persisted mismatch: 0/20
- Success requests: 25/160, Failure requests: 135/160
- 실패 135건 전부 `CannotAcquireLockException`(MySQL 1213), 다른 종류 예외 없음

## Pessimistic Lock (#35)

`AuctionRepository.findByIdForUpdate()`(`@Lock(PESSIMISTIC_WRITE)`)로 no-lock과 동일한
frozen workload를 20회 반복한 raw 재집계 결과:

- runs: 20 / request attempts: 160
- Any post-state invariant violation: **0/20** (price mismatch/winner mismatch/lost
  update/success-persisted mismatch 전부 0/20)
- Success requests: 61/160, Failure requests: 99/160
- 실패 99건 전부 `BidAmountTooLowException`(business rejection), `CannotAcquireLockException`
  0/160, 그 외 예외 0/160

## Comparison

### Correctness (Run-level)

| Metric | No-lock (#34) | Pessimistic Lock (#35) |
| --- | ---: | ---: |
| Runs | 20 | 20 |
| Any invariant violation | 3/20 | 0/20 |
| Price mismatch | 3/20 | 0/20 |
| Winner mismatch | 3/20 | 0/20 |
| Lost update | 3/20 | 0/20 |
| Success/persisted mismatch | 0/20 | 0/20 |

### Request outcomes (Request-level)

| Metric | No-lock (#34) | Pessimistic Lock (#35) |
| --- | ---: | ---: |
| Attempts | 160 | 160 |
| Success | 25 | 61 |
| Business rejection | raw에 컬럼 없음(#34는 이 개념을 구분하지 않음) | 99 (전부 `BidAmountTooLowException`) |
| CannotAcquireLockException | 135 | 0 |
| Other exception | 0 | 0 |

No-lock(#34) raw에는 `businessRejectionCount` 컬럼이 없다 — #34 시점에는 실패가 전부
`CannotAcquireLockException`이라 이 구분이 필요 없었다. 억지로 0으로 채우지 않고 "해당
개념을 구분하지 않았음"으로만 표기한다. `3/20`(run-level)과 `135/160`·`99/160`
(request-level)은 서로 다른 지표이며 혼용하지 않는다.

## Representative No-lock Failure

- run: 3 (#34)
- persisted max Bid: 45000
- Auction.currentPrice: 15000
- max Bid bidder: 29
- Auction.currentWinner: 23
- violations: `PRICE_MISMATCH: currentPrice=15000 actualMaxBid=45000`, `WINNER_MISMATCH: currentWinner=23 actualMaxBidder=29`, `LOST_UPDATE: a Bid amount exceeds Auction.currentPrice`

다른 두 violation run(4, 7)의 원본 값은 `raw/no-lock-correctness.csv`와
`raw/logs/no-lock-run-04.log`, `raw/logs/no-lock-run-07.log`에 그대로 남아 있다.

## Pessimistic Result

20개 run 전부 violation이 없어 "대표 실패 run"은 없다. 대신 결과가 일관적인지 보여주는
대표 run 하나(run 1)의 raw 값:

- run: 1 (#35)
- successCount: 2, failureCount: 6 (전부 `BidAmountTooLowException`)
- persisted Bid count: 2
- Auction.currentPrice: 50000, max persisted Bid: 50000 (일치)
- Auction.currentWinner: 12, max Bid bidder: 12 (일치)
- violations: 없음

20개 run 전부 `Auction.currentPrice`가 그 run에서 성공한 최고 금액 입찰(worker 순서상 항상
50000까지 도달 가능한 최댓값)과 일치했고, `currentWinner`도 실제 최고 입찰자와 항상
일치했다. 원본은 `raw/pessimistic-correctness.csv`, `raw/logs/pessimistic-run-01~20.log`.

## Interpretation

### No-lock (#34)

동일한 frozen no-lock concurrency workload(worker=8, delay=1000ms)로 20회 수행한 결과,
3개 run에서 post-state invariant violation을 관찰했다. `@Version`이 제거된 상태에서
`UPDATE auctions ...`에 버전 조건이 없기 때문에, 여러 트랜잭션이 같은 stale `Auction`
상태를 읽고 각자 커밋에 성공할 수 있는 구조였다 — 관찰된 3개 violation run의 최종 상태는
이 가능성과 부합한다. **다만 어느 트랜잭션이 몇 번째로 commit됐는지는 로그로 특정하지
않았으므로, "특정 트랜잭션이 이전 값을 덮어썼다"를 확정 사실로 서술하지 않는다.**

### Pessimistic Lock (#35)

동일 frozen correctness workload에서 `PESSIMISTIC_WRITE` 적용 후 20회 중
**post-state invariant violation이 관찰되지 않았다(0/20)**. `SELECT ... FOR UPDATE`가
Auction row에 대한 접근을 직렬화해, 각 트랜잭션이 항상 직전에 commit된 최신
`currentPrice`를 기준으로 검증/수정하기 때문으로 해석된다 — no-lock에서 관찰된 stale
read 기반 lost update의 전제 조건(여러 트랜잭션이 동시에 같은 stale 상태를 읽는 것) 자체가
사라진 것과 일치한다. 대신 뒤에 lock을 얻은 bidder는 이미 갱신된 `currentPrice`를 보고
`BidAmountTooLowException`으로 정상 거부되는 경우가 크게 늘었다(no-lock 25건 → pessimistic
61건 성공, 그러나 실패 99건 전부 business rejection이지 DB 예외가 아니다).

**Fact**: `@Version` 없음, `PESSIMISTIC_WRITE` 1곳에만 적용, 실제 SQL에 `for update` 포함,
0/20 invariant violation, `CannotAcquireLockException` 0/160, 실패 99건 전부
`BidAmountTooLowException`.

**Inference**: 위 fact들이 "row 접근이 직렬화되어 stale read가 원천적으로 발생하지 않았다"는
설명과 부합한다는 것이지, 각 트랜잭션이 정확히 몇 번째로 lock을 획득했는지를 로그로 추적해
확정한 것은 아니다.

## Limitations

- **이 결과를 운영 환경의 race 발생 확률/실패율로 해석하지 않는다.** `delay=1000ms`는
  운영 트래픽에서 절대 발생하지 않는 인위적으로 확대된 read-modify-write 창이다.
  퍼센트(%)나 "재현율"로 환산해 운영 발생 가능성처럼 표현하지 않는다.
- 통제된 correctness stress workload의 160개 request attempt 중 no-lock은 135개가
  `CannotAcquireLockException`으로, pessimistic lock은 99개가 `BidAmountTooLowException`
  (business rejection)으로 종료됐다 — 이 값들을 "no-lock/pessimistic failure rate"나
  운영 요청 실패율로 표현하지 않는다.
- **Pessimistic Lock의 `0/20`은 "모든 환경에서 정합성을 완벽히 보장한다"를 의미하지 않는다.**
  single application instance, single MySQL, single Auction row contention, frozen test
  workload라는 이번 검증 범위 안에서의 관찰값이다.
- 각 run의 elapsed time은 로그에 남아 있지만, `delay=1000ms`가 포함돼 있어 이번 실험에서는
  성능 지표(median/p95/throughput)로 해석하지 않는다 — 특히 Pessimistic Lock은 첫
  트랜잭션이 row lock을 잡은 채 delay만큼 대기하므로 지연이 no-lock보다 커질 수 있지만,
  이는 race reproduction을 위한 인위적 조건이다. 성능 비교는 별도(#36)로 수행한다.
- 트랜잭션 commit/lock 획득 순서를 로그로 추적하지 않아, 두 실험 모두 원인 설명은
  §Interpretation의 inference로만 서술한다.
- Pessimistic Lock의 두 트랜잭션 간 실제 블로킹은 별도의 격리된 lock-wait 테스트가 아니라
  #35 20회 본 실험 자체의 결과(0 invariant violation, `CannotAcquireLockException` 0/160,
  모든 run이 타임아웃 없이 정상 종료)로부터 추론했다 — §protocol.md Pessimistic Lock
  Strategy 참고.

## Remaining Questions

- (#34에서 제기됨, #35로 해소) "pessimistic lock 적용 후 violation이 0/20으로 사라지는가"
  → 이번 실험에서 0/20으로 확인됐다.
- No-lock에서 3개 violation run 모두 success=2였지만, success=2인 다른 run(2, 10)은
  violation이 없었다 — success count만으로는 violation 여부를 예측할 수 없었다. 정확히
  어떤 타이밍 조합이 violation으로 이어지는지는 이번 실험 범위 밖이다.
- Pessimistic Lock에서 request-level success가 25→61로 늘어난 것은 lock 대기 후 재시도
  없이 순서대로 처리되기 때문으로 보이지만, 정확한 lock 대기열 순서는 로그로 추적하지
  않았다 — 필요하면 MySQL `performance_schema`/`information_schema.INNODB_LOCK_WAITS`
  기반의 별도 관찰이 필요하다(이번 범위 밖).
- No-lock vs Pessimistic Lock의 실제 지연/처리량 차이는 delay를 제거한 별도 #36 성능
  실험에서 다룬다.
