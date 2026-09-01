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
(`ProxyTrigger.None`)은 `#73-1`에서 실제 production 호출부(`AuctionStartService`)가
생겼다 - 이 엔진을 그대로 호출할 뿐 새 계산식은 없다. 상세는 아래
`Auction Lifecycle Scheduler` 절 참고.

## Award → BackupOffer → Order 흐름

경매 종료 후 낙찰/포기/차순위 이양 흐름(#56). Result는 별도 테이블이 아니라 아래 네 도메인의
상태를 조합해 매 조회마다 계산한다(`AuctionResultQueryService`, side-effect free).

```text
[경매 종료] --settle()(#73-2: AuctionEndService가 LIVE→ENDED 직후 같은 트랜잭션에서 호출)--> [winner Order: PAYMENT_PENDING]
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

[PAYMENT_PENDING] --GET /orders/{id}--> 조회(§12 전체 shape)
                  --POST /orders/{id}/pay--> Order: PAID(Mock, 재호출은 상태 멱등 200)
                  --scheduler, deadline 초과--> Order: PAYMENT_EXPIRED
                                                 + PAYMENT_EXPIRED penalty 1건
                                                 + User.noShowCount++/bidRestrictedUntil 갱신
                                                 + 다음 순위 BackupOffer(있으면)
[WAITING BackupOffer] --scheduler, deadline 초과--> BackupOffer: EXPIRED
                                                     + 다음 순위 BackupOffer(있으면)
```

- **settlement 호출부**: `#73-2`에서 `AuctionEndService`가 `LIVE→ENDED` 전환 직후 같은
  트랜잭션 안에서 `AuctionSettlementService.settle()`을 호출하도록 연결했다(`settle()`
  내부 로직은 그대로 재사용, 복제하지 않음). `#57`의 두 scheduler(결제 기한 만료/차순위
  제안 만료)는 여전히 ENDED 이후 단계만 다룬다 - `#73`은 그 앞 단계(SCHEDULED→LIVE/
  LIVE→ENDED)를 담당해 lifecycle 전체가 이어졌다. 상세는 아래
  `Auction Lifecycle Scheduler` 절 참고.
- **rank 2/3까지만 후보다**: `BackupCandidateSelector`가 forfeit/decline/두 만료 scheduler
  전부가 공유하는 단일 선정 로직이다(#56, #57-2에서 재사용만 하고 새 순위 정책을 만들지
  않았다).
- **Order 조회/Mock 결제(§12/§13)와 결제 기한 만료 scheduler(#57-1/#57-2)**: `GET
  /orders/{id}`, `POST /orders/{id}/pay`, `OrderExpirationScheduler`. Mock 결제라
  실제 PG 연동은 없다 - `Order.status`만 바꾸고 `Auction.status`는 건드리지 않는다. 두
  scheduler(`payment.expiration`/`backup-offer.expiration`)는 기본 비활성이고(테스트
  간섭 방지), 실제 API가 뜨는 `dev` profile에서만 명시적으로 켜져 있다.
- **UserPenalty 완성(#57-2)**: `GET /me/penalties`가 `noShowCount`/`bidRestricted`/
  `bidRestrictedUntil`/이력의 single source of truth다. `noShowCount`는 `PAYMENT_EXPIRED`
  penalty만 세고 `FORFEITED`는 세지 않는다(사용자 확정 정책) - `bidRestrictedUntil`은 고정
  기간(설정값, 기본 7일)이며 회차별 escalating은 적용하지 않는다.
- **v1 제외 범위(계약 명시)**: 실제 PG 연동, 결제수단 선택 API, 환불/webhook, 배송, 강제
  만료용 production API는 FINAL contract §13이 v1 범위에 넣지 않은 항목이라 구현하지
  않았다.
- 상세 트랜잭션 순서(lock ordering)/DB invariant/알려진 gap(특히 accept/decline의 소유자
  검증 부재, FORFEITED의 bidRestrictedUntil 미반영):
  [`docs/api/auction-api-contract-gap.md`](docs/api/auction-api-contract-gap.md)의
  `#56-1`~`#57 Implementation Notes` 참고.

## Auction Lifecycle Scheduler

`#73`. `SCHEDULED → LIVE`/`LIVE → ENDED`를 시간 기반으로 자동 전환하는 production
scheduler. FINAL API contract는 변경 없음(새 endpoint 없음, 기존 20개 그대로).

```text
[SCHEDULED] --startAt 도달--> [LIVE] + RESERVED AutoBidSetting 일괄 정산(ProxyTrigger.None)
[LIVE]      --endAt 도달-----> [ENDED] + #56 AuctionSettlementService.settle() 호출
```

- **latest endsAt 기준**: 종료 판정은 스케줄러가 candidate를 고를 때 본 시각이 아니라,
  `AuctionEndService`가 Auction을 다시 잠근 뒤 읽은 "현재" `endAt`이다 - 종료 연장(`#43`
  `maybeExtend()`)으로 밀린 경매를 최초 예정 시각 기준으로 조기 종료하지 않는다. 스케줄러는
  candidate id만 넘기고 시각값 자체는 넘기지 않는다.
- **RESERVED 활성화**: 시작 시 `ProxyPriceEngine.resolve(trigger=ProxyTrigger.None)`을
  그대로 호출한다(`#42`가 미리 만들어 둔 계산, 새 scheduler 전용 bidding rule 없음) -
  유효한 cap은 ACTIVE, finalPrice에 못 미치면 CAP_REACHED, 동일 cap은 기존 FIRST-IN
  WINS(§0.12).
- **설정**: `auction.lifecycle.batch-size`(두 scheduler 공유) /
  `auction.lifecycle.start.cron`·`.enabled` / `auction.lifecycle.end.cron`·`.enabled`.
  base 기본값은 `false`(`#57-2`와 동일한 이유 - MySqlIT가 `local` profile을 빌려 쓰는데
  기본 활성화하면 간섭한다, `#58-3`에서 실측), `dev` profile에서만 명시적으로 `true`. 새
  worker profile은 만들지 않았다.
- **운영 한계**: 리더 선출/분산 조정이 없는 단일 application scheduler 가정이다 - 여러
  인스턴스가 뜨면 각자 독립적으로 polling한다. MySQL `PESSIMISTIC_WRITE`가 실제 상태
  중복 반영은 막지만(동시 invocation을 실제 MySQL로 검증함), 인스턴스 수만큼 같은
  candidate를 중복 조회/lock 경합하는 비효율은 남는다 - leader election은 이번 범위 밖.
- **Result는 여전히 derived다**: 이번에도 별도 Result entity/row를 만들지 않았다 - `GET
  /result`는 Auction/Order 상태를 매 조회마다 계산하는 기존 구조(`#56`) 그대로다.
- 상세 구현/lock 순서/MySQL IT 목록:
  [`docs/api/auction-api-contract-gap.md`](docs/api/auction-api-contract-gap.md)의
  `#73 Implementation Notes` 참고.

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