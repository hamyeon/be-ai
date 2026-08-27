# No-lock Correctness Main Experiment (#34)

`docs/experiments/concurrency/raw/no-lock-correctness.csv`(20 rows)를 집계한 결과다.
raw data 자체는 수정하지 않았고, 이 문서는 그 raw data의 요약/집계만 담는다.

## Conditions

- baseline tag: `exp/baseline-no-lock`
- baseline commit: `5bfe881e48f5400b3279c3d04b4191e427742381`
- MySQL: `8.4.10` (Testcontainers `mysql:8.4`)
- isolation: `REPEATABLE-READ`
- Hikari maximumPoolSize: `20`
- workers/bidders: `8` / `8` (1 thread = 1 bidder = 1 request)
- delay: `1000ms` (test-only, `AuctionRepository.findById()` 반환 직후)
- initial price: `10000`
- bid increment: `5000`

상세는 [protocol.md](./protocol.md), [environment.md](./environment.md) 참고.

## Sample

- runs: 20
- request attempts: 160 (20 runs × 8 concurrent requests)

## Run-level Results

| Metric | Result |
| --- | ---: |
| Any post-state invariant violation | 3 / 20 |
| Price mismatch | 3 / 20 |
| Winner mismatch | 3 / 20 |
| Lost update | 3 / 20 |
| Success/persisted mismatch | 0 / 20 |

세 지표(price/winner/lost update)가 매번 같은 3개 run(3, 4, 7)에서 함께 관찰됐다 —
서로 다른 run에서 개별적으로만 나타난 경우는 없었다.

## Request-level Results

| Metric | Result |
| --- | ---: |
| Attempts | 160 |
| Success | 25 |
| Failure | 135 |
| CannotAcquireLockException | 135 |
| Other exceptions | 0 |

`7/20`(run-level)과 `135/160`(request-level)은 서로 다른 지표이며 혼용하지 않는다. 실패한
135건은 전부 `CannotAcquireLockException`(MySQL 1213)이었고, 다른 종류의 예외는 없었다.

## Representative Failure

- run: 3
- persisted max Bid: 45000
- Auction.currentPrice: 15000
- max Bid bidder: 29
- Auction.currentWinner: 23
- violations: `PRICE_MISMATCH: currentPrice=15000 actualMaxBid=45000`, `WINNER_MISMATCH: currentWinner=23 actualMaxBidder=29`, `LOST_UPDATE: a Bid amount exceeds Auction.currentPrice`

다른 두 violation run(4, 7)의 원본 값은 `raw/no-lock-correctness.csv`와
`raw/logs/no-lock-run-04.log`, `raw/logs/no-lock-run-07.log`에 그대로 남아 있다.

## Interpretation

동일한 frozen no-lock concurrency workload(worker=8, delay=1000ms)로 20회 수행한 결과,
3개 run에서 post-state invariant violation을 관찰했다. violation이 발생한 3개 run 모두
price mismatch, winner mismatch, lost update가 함께 나타났다 — `Auction.currentPrice`가
실제 persisted 최고 `Bid` 금액보다 낮게 남았고, 그 결과 `currentWinner`도 실제 최고
입찰자와 달랐다. 성공으로 보고된 요청 수와 실제 persisted `Bid` 수는 20회 전부 일치했다
(success/persisted mismatch 0/20) — 실패한 트랜잭션(`CannotAcquireLockException`)은
Bid insert를 포함해 항상 온전히 롤백됐다는 뜻이며, ACID 원자성 자체가 깨진 것은 아니다.

`@Version`이 제거된 상태에서 `UPDATE auctions ...`에 버전 조건이 없기 때문에, 여러
트랜잭션이 같은 stale `Auction` 상태를 읽고 각자 커밋에 성공할 수 있는 구조였다 — 관찰된
3개 violation run의 최종 상태는 이 가능성과 부합한다. **다만 어느 트랜잭션이 몇 번째로
commit됐는지는 로그로 특정하지 않았으므로, "특정 트랜잭션이 이전 값을 덮어썼다"를 확정
사실로 서술하지 않는다** — 확인된 사실은 (1) 버전 조건 없는 UPDATE로 lost update가
구조적으로 가능했고 (2) 관찰된 3개 run의 최종 상태가 그 가능성과 부합한다는 것뿐이다.

## Limitations

- **이 결과를 운영 환경의 race 발생 확률이나 no-lock failure rate로 해석하지 않는다.**
  `delay=1000ms`는 운영 트래픽에서 절대 발생하지 않는 인위적으로 확대된 read-modify-write
  창이다. "3/20"은 오직 "이 통제된 조건에서 harness가 위반을 재현할 수 있는가"만을 의미한다.
  퍼센트(%)나 "재현율"로 환산해 운영 발생 가능성처럼 표현하지 않는다.
- MySQL/InnoDB 자체의 내부 lock은 여전히 존재한다 — 관찰된 135건의
  `CannotAcquireLockException`(MySQL 1213 Deadlock)이 그 증거이며, 이는 correctness
  violation과는 별도의 contention/request-failure 지표다.
- 각 run의 elapsed time은 로그에 남아 있지만, `delay=1000ms`가 포함돼 있어 이번 실험에서는
  성능 지표(median/p95/throughput)로 해석하지 않는다 — 성능 실험은 별도(#36)로 수행한다.
- 트랜잭션 commit 순서를 로그로 추적하지 않아 lost-update의 정확한 발생 메커니즘(어느
  트랜잭션이 몇 번째로 commit됐는지)은 확정 사실이 아니라 §Interpretation의 inference로만
  서술한다.

## Remaining Questions

- 3개 violation run 모두 success=2인 반면, 나머지 17개 run은 success=1 또는 2였지만
  violation이 없었다 — success count만으로는 violation 여부를 예측할 수 없었다(예: run 2,
  10도 success=2였지만 violation 없음). 정확히 어떤 타이밍 조합이 violation으로 이어지는지는
  이번 실험 범위 밖이다.
- pessimistic lock 적용 후(#35) 동일 20회 조건에서 violation이 0/20으로 사라지는지, 그리고
  request-level 실패 패턴(`CannotAcquireLockException` 비율)이 어떻게 달라지는지가 다음
  비교 대상이다.
