# be-ai

## Concurrency Experiments

수동 입찰(`Auction`) read-modify-write 경쟁을 no-lock/pessimistic-lock 등 동일 조건에서
비교하기 위한 실험. 프로토콜/환경/raw data/요약은 `docs/experiments/concurrency/`에 분리해
관리한다.

**문제**: 동시에 여러 사용자가 같은 `Auction` row를 read-modify-write(현재가 확인 → 입찰가
검증 → 갱신)하면 lost update/stale read가 발생할 수 있다.

**후보**: JVM local lock(synchronized/ReentrantLock), 단일 conditional UPDATE, SERIALIZABLE
isolation, Pessimistic Lock(`SELECT ... FOR UPDATE`), Optimistic Lock + retry, Redisson(분산
락), Queue/Kafka(event-driven serialize) — 전체 비교표는
[summary.md §Alternatives Considered](docs/experiments/concurrency/summary.md#alternatives-considered-40)
참고.

**실험** (delay로 race window를 확대한 통제 실험, #34/#35 correctness + #36 performance)
- No-lock (#34): 동일 frozen workload 20회 중 3회 post-state invariant violation 관찰.
- Pessimistic Lock (#35): 동일 frozen workload(독립변수는 `PESSIMISTIC_WRITE` 적용 여부
  하나만) 20회 중 0회 관찰.
- Performance(delay 없이 측정, #36-A): No-lock overall median 27.3ms/p95 38.4ms(attempt
  253.8/s) vs Pessimistic Lock median 60.2ms/p95 111.7ms(attempt 82.6/s). outcome mix가
  달라(No-lock은 DB 예외 위주, Pessimistic은 business rejection 위주) 이 차이를 "Pessimistic
  Lock의 순수 오버헤드"로 단정하지 않는다 — 상세는 summary 참고.

**선택**: Pessimistic Lock 채택. 단일 MySQL `Auction` row가 contention point이고 authoritative
read부터 직렬화가 필요한 구조라, correctness 실험에서 0/20 violation을 관찰한 이 방식을
택했다(latency 비용은 감수 — 위 performance 수치 참고). 이 프로젝트에 실제로 구현/도입된
concurrency-control 방식은 **Pessimistic Lock(`SELECT ... FOR UPDATE`) 하나뿐**이다 — 아래
Redisson 등은 검토만 했고 코드에 없다.

**Idempotency vs Lock — 서로 대체 관계가 아니다.**

```text
Idempotency        — 동일 user / 동일 logical request retry의 중복 처리 방지
                      (IdempotencyClaimService, UNIQUE(user_id, operation_scope, idempotency_key))
Concurrency control — 서로 다른 user/request가 동일 Auction row를 동시에 RMW하는 문제 해결
                      (Pessimistic Lock, findByIdForUpdate)
```

`IdempotencyClaimService`가 있다고 해서 pessimistic lock이 불필요해지지 않는다 - 전자는 "같은
사람이 같은 요청을 두 번 보냈는가", 후자는 "다른 사람들이 동시에 같은 row를 고쳤는가"를
다루는 별개의 문제라 이 프로젝트는 둘 다 쓴다(입찰/자동입찰 CREATE·UPDATE/BackupOffer
accept는 Idempotency-Key + Pessimistic Lock을 함께 쓰고, forfeit/decline은 Idempotency 없이
Pessimistic Lock만 쓴다 - Idempotency-Key가 FINAL contract §0.11이 지정한 4개 endpoint에만
필요하기 때문이다).

**Redisson(분산 락) 미도입 근거**: 검토는 했으나 **구현하지 않았다.** 이 프로젝트의 contention
point는 단일 MySQL row(`Auction`)이고, 모든 write 경로가 이미 같은 DB transaction 안에서
`SELECT ... FOR UPDATE`로 직렬화된다 - Redis라는 별도 coordination 시스템을 추가로 들일
이유가 없다. 다중 DB나 DB transaction 바깥 resource까지 묶는 distributed coordination
요구가 생기면 재검토 대상이다. (근거 전문:
[summary.md 대안 비교표](docs/experiments/concurrency/summary.md#alternatives-considered-40))

- 결과 요약: [docs/experiments/concurrency/summary.md](docs/experiments/concurrency/summary.md)
- 상세 프로토콜: [docs/experiments/concurrency/protocol.md](docs/experiments/concurrency/protocol.md)
- 실행 환경: [docs/experiments/concurrency/environment.md](docs/experiments/concurrency/environment.md)
- Raw data: [no-lock-correctness.csv](docs/experiments/concurrency/raw/no-lock-correctness.csv), [pessimistic-correctness.csv](docs/experiments/concurrency/raw/pessimistic-correctness.csv), [no-lock-performance.csv](docs/experiments/concurrency/raw/no-lock-performance.csv), [pessimistic-performance.csv](docs/experiments/concurrency/raw/pessimistic-performance.csv)

## Proxy Bidding 구조

자동입찰(AutoBid) 가격 결정 엔진(`autobid/proxy/ProxyPriceEngine`)은 두 트리거만 다룬다(#41
후속에서 실제 구현, 그 이전엔 정책 문서만 있는 stub이었다):

```text
1. AutoBid 등록/수정(LIVE) - resolveForAutoBidEntrant()
2. Manual Bid 성공 직후의 즉시 반격 - resolveAfterManualBid()
```

두 메서드는 같은 pairwise 비교 로직(effectiveCap 계산 + FIRST-IN WINS tie-break)을 공유한다.
경쟁자 판정은 "다른 사용자의 ACTIVE AutoBid" 존재 여부가 아니라 `Auction.currentWinner`/
`currentPrice`를 기준으로 한다. `SCHEDULED → LIVE` 전환 시점의 RESERVED 일괄 정산
(`ProxyTrigger.None`)은 순수 계산 로직 자체는 있지만, 그걸 실제로 호출하는 production
진입점(lifecycle scheduler)이 아직 없다 - `DEFERRED UNTIL LIFECYCLE INTEGRATION`으로 남아있다
(아래 Award 흐름의 settlement와 동일한 성격의 gap). 상세는
[auction-api-contract-gap.md의 `Proxy Bidding 실제 구현(#41 후속)`](docs/api/auction-api-contract-gap.md)
절 참고.

## Award → BackupOffer → Order 흐름

경매 종료 후 낙찰/포기/차순위 이양 흐름(#56). Result는 별도 테이블이 아니라 아래 네 도메인의
상태를 조합해 매 조회마다 계산한다(`AuctionResultQueryService`, side-effect free).

```text
[경매 종료] --settle()(명시적 command, 아직 scheduler 없음)--> [winner Order: PAYMENT_PENDING]
                                                                paymentDeadline = endsAt + 24h

[winner] --POST /award/forfeit--> Order: CANCELED
                                   + FORFEITED penalty 1건
                                   + BackupOffer(rank 2): WAITING, deadline = createdAt + 24h

[rank 2] --POST /backup-offers/{id}/accept--> BackupOffer: ACCEPTED
                                               + 새 Order(rank 2 본인): PAYMENT_PENDING
                                                 purchasePrice = offer.purchasePrice(원 낙찰가 X)
                                                 paymentDeadline = 수락 시각 + 24h
      --POST /backup-offers/{id}/decline--> BackupOffer: DECLINED
                                             + BackupOffer(rank 3): WAITING (있으면)

[rank 3] --accept/decline--> 위와 동일, 단 decline 시 추가 제안 없음(#56-0: rank 4는 후보 아님)
```

- **settlement 호출부 자체는 없다**: `AuctionSettlementService.settle()`은 테스트/향후
  lifecycle scheduler가 호출하는 명시적 command다 - `LIVE→ENDED` 전환을 감지해 자동으로
  부르는 production 코드가 아직 없다(Proxy Bidding의 `ProxyTrigger.None`과 동일한
  DEFERRED 상태).
- **rank 2/3까지만 후보다**: `BackupCandidateSelector`가 forfeit(최초 rank 2 생성)과
  decline(다음 순위 생성)이 공유하는 단일 선정 로직이다.
- **결제(Order 조회/Mock 결제) endpoint 자체가 없다**: `GET /orders/{id}`, `POST
  /orders/{id}/pay`는 아직 구현되지 않았다 - Order는 지금 이 흐름 내부에서만 생성/전이된다.
- 상세 트랜잭션 순서(lock ordering)/DB invariant/알려진 gap(특히 accept/decline의 소유자
  검증 부재): [`docs/api/auction-api-contract-gap.md`](docs/api/auction-api-contract-gap.md)의
  `#56-1`~`#56-3 Implementation Notes` 참고.

## Auction API Contract

```text
Auction API contract frozen (#36-B)
- canonical contract 확정: docs/auction-api-spec-final.md
- 현재 구현과의 gap은 별도 audit 문서로 관리
- 미구현 endpoint(17/20) 및 deferred implementation gap(정렬 검증/닉네임 마스킹/
  오류 코드 매핑/40909 처리) 존재 — "계약 확정"과 "구현 완료"는 다른 것으로 취급한다
```

- canonical source of truth: [docs/auction-api-spec-final.md](docs/auction-api-spec-final.md)
- freeze 상태 / endpoint별 gap / deferred 항목: [docs/api/auction-api-contract-gap.md](docs/api/auction-api-contract-gap.md)
- 짧은 포인터 문서: [docs/api/README.md](docs/api/README.md)