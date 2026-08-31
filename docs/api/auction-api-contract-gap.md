# Auction API Contract Freeze Audit (#36-B)

## Contract Status

```text
API CONTRACT: FROZEN
```

Freeze baseline: 2026-08-20 · Finalized in: #36 (tie-break policy was the last open
contract-level question; resolved 2026-08-28 — see §Resolved Policies).

**Contract frozen ≠ implementation complete.** This freeze fixes the external API shape
and business semantics that frontend/backend agree to build against. It does not claim
that 20/20 endpoints exist or that every confirmed rule is already enforced by the server.
Gaps between "what the contract says" and "what the code currently does" are tracked below
as Deferred Implementation Gaps / Not Implemented Yet — neither category is a freeze
blocker.

## Source of Truth

`docs/auction-api-spec-final.md` (repo root `docs/`, not `docs/api/`). Freeze metadata
(`Contract Status: FROZEN`, baseline date) and the tie-break policy (§0.12) were added
directly to that file in this pass, per explicit instruction — the rest of its content is
unchanged.

## Resolved Policies

| Policy | Resolution |
| --- | --- |
| Tie-break | **먼저 접수/등록된 요청이 우선한다** (first-in wins). Proxy Bidding에서 동일 `maxAmount` AutoBid 경쟁도 동일 원칙. Ordering을 구현할 실제 DB 컬럼(생성시각 정밀도/auto-increment/sequence)은 이 계약에서 지정하지 않고 Proxy Bidding 구현 시점의 기술적 세부사항으로 남긴다. 명세 §0.12에 반영. |
| Direct bid alignment | `amount >= minNextBidAmount` 이고 `(amount - currentPrice) % bidIncrement == 0`이어야 하며, 불만족 시 `409 / 40913 BID_NOT_ALIGNED`. (명세 §9) |
| AutoBid cap alignment | `maxAmount`는 배수 정렬을 요구하지 않는다 — 실효 상한(ceiling)으로 동작. (명세 §5) |
| Manual bid cancels AutoBid | 자동입찰 사용 중 직접 입찰 시 기존 `AutoBidSetting` → `CANCELED`, `autoBidCanceled=true`. (명세 §9) |
| AutoBid re-registration | `CANCELED`는 terminal — 재등록 시 새 `AutoBidSetting` 생성(기존 설정을 되살리지 않음). (명세 §5) |
| RESERVED cap modification | 상향/하향/동일값 모두 허용(`>= minCapAmount`). (명세 §7) |
| ACTIVE/CAP_REACHED modification | 상향만 허용, 아니면 `409 / 40907 CAP_NOT_INCREASED`. `40906`(minCapAmount 미달)과 동시 위반 시 `40906`이 우선한다 — 공통 하한 체크가 상향 체크보다 먼저 실행된다(#41, 명세엔 순서가 명시되지 않아 확정한 판단). (명세 §7) |
| Idempotency | 4개 지정 endpoint, `UNIQUE(user_id, operation_scope, idempotency_key)`, 누락→400/40004, 동일 payload→replay, 다른 payload→409/40905. (명세 §0.11) |
| Numeric error contract | §0-A 오류 코드표 40001~40915 전부 확정. |
| Concurrent conflict contract | `40909 CONCURRENT_CONFLICT`(409) — 프론트 자동 재시도 **최대 1회**만 허용되는 유일한 코드. (명세 §0-A) |
| Nickname masking | 3자 이상 → 앞 3글자+`****`, 1~2자 → 첫 1글자+`****`, 별표 항상 4개. (명세 §0.9) |

이 문서 작성 시점 기준, 위 항목 외에 canonical spec(2994줄 전체 재확인)과 실제 코드 사이에서
**"계약 자체가 두 가지로 갈리거나 미정인" 경우는 추가로 발견되지 않았다** — 나머지 차이는
모두 아래 §Deferred Implementation Gaps / §Not Implemented Yet에 속한다(계약은 하나로
확정, 구현이 아직 못 따라간 것).

## Deferred Implementation Gaps

계약은 확정됐지만 현재 서버 구현이 아직 따르지 않는 항목. 사용자가 인지하고 후속 issue로
넘기기로 결정했으며, 이번 #36에서는 production 코드를 수정하지 않았다.

| Gap | Contract | Current Implementation | Action |
| --- | --- | --- | --- |
| Direct bid alignment 검증 | **RESOLVED (#43)** | `Auction.placeManualBid()`가 최소금액(`40904`) 통과 이후 `(amount - currentPrice) % bidIncrement != 0`을 확인해 `BidNotAlignedException`(`40913`)을 던진다. AutoBid `maxAmount`에는 적용하지 않는다(§5, 실효 상한) | — |
| Nickname masking (`/bids`) | **RESOLVED (#55)** | `NicknameMasker`를 `BidResponse`에 적용, `isMine`/`isHighest`/`bidType` 전부 FINAL contract shape로 반환 — 아래 `#55 Implementation Notes` 참고 | — |
| Numeric error mapping — `AuctionNotFoundException` | **RESOLVED (#46)** | `AuctionNotFoundException`을 40402→40401로 옮겼다. Order 도메인이 아직 없어 40402가 다른 예외에 점유되지 않은 상태를 확인한 뒤 단독으로 renumbering — 아래 `#46 Implementation Notes` 참고 | — |
| Numeric error mapping — `UserNotFoundException` | Frozen (§0-A) | 스펙의 40403(BACKUP_OFFER_NOT_FOUND) 자리를 `UserNotFoundException`이 여전히 점유 중 — BackupOffer 도메인이 아직 없어 현재는 실제 충돌이 없다 | 후속 issue — BackupOffer 구현 전에 정리 필요(그때 가서 번호 충돌 발생) |
| `40909 CONCURRENT_CONFLICT` mapping | Frozen (§0-A, HTTP 409 / code 40909 / 재시도 최대 1회) | 없음 — DB lock 예외(`CannotAcquireLockException`, #34 raw에서 135/160 관찰)가 catch-all `Exception` 핸들러로 떨어져 **500 / 50001**로 응답됨 | 후속 issue |
| Auth: Bearer token | Frozen (§0.1) | Mock 인증(`X-User-Id` 헤더 + `MockAuthInterceptor`) | 후속 issue(인증 시스템 도입 시) |
| Time 직렬화: ISO-8601 절대시각 (저장된 timestamp) | **RESOLVED** | 아래 `Time Policy (#41 후속)` 참고 — Asia/Seoul 고정 정책으로 `+09:00` 절대시각을 전 응답에 일관 적용 | — |
| Endpoint #1 응답 shape (`myState`, `product`, `seller`, AI 필드, `isLiked`/`likeCount`, `finalPrice`, `serverTime`, `minNextBidAmount`/`minCapAmount`) | **RESOLVED (#55)** | `AuctionDetailResponse`를 FINAL contract 중첩 shape로 전면 재작성 — 아래 `#55 Implementation Notes` 참고. `product.subName`/`seller.completedSalesCount`는 남은 gap(바로 아래 두 행) | — |
| `product.name` / `product.subName` | Frozen (§1, 둘 다 필수 O) | `Product` 엔티티에 전용 컬럼이 없다(`brand`/`model`/`colorway`만 구조화 필드로 존재) — `ProductDisplayName` 유틸로 `brand+model+colorway`를 합성해 `name`으로, `model`을 `subName`으로 임시 대체(#55) | 후속 issue — 전용 `name`/`subName`(또는 한국어/영문 구분) 컬럼 도입 여부 결정 필요(스키마 변경) |
| `seller.completedSalesCount` | Frozen (§1, Int, 필수 O — non-null) | **DEFERRED DATA SOURCE GAP.** Order 도메인이 없어(§Not Implemented Yet #12/#13) 실제 판매 완료 건수를 집계할 source가 없다. `AuctionQueryService`가 non-null 계약을 어기지 않기 위해 항상 `0`을 반환하지만(#55), 이 값은 "실제로 0건"이라는 의미가 아니다 — shape만 충족하고 semantics는 아직 미충족이다 | Order 도메인이 #56에서 구현되면 실제 카운트로 교체 |
| Endpoint #9 응답 shape (`minNextBidAmount`, `highestBidderMasked`, `isHighestBidder`, `autoBidCanceled`, `proxyResponded`, `endsAt`) | **RESOLVED (#41 후속)** | `PlaceBidResponse`를 FINAL contract shape로 전면 재작성. `extensionCount`도 #43에서 마저 추가됨(바로 아래 행) | — |
| `/live.extensionCount` / `/live.maxExtensions` / `POST bids.extensionCount` | **RESOLVED (#43)** | 종료 연장 정책(트리거 1분 이내/+3분/최대 3회, §0.13)을 확정하고 `Auction.extensionCount`/`MAX_EXTENSIONS`를 실제 도메인 값으로 연결했다. 아래 `#43 Implementation Notes` 참고 | — |
| AutoBid POST/PATCH의 `bidOccurred`/`resultingBidAmount`/`isHighestBidder` | **RESOLVED (#42)**, 문서만 미갱신 상태였음 | 코드는 #42에서 이미 `ProxyPriceEngine` 실제 resolution 결과를 반환하도록 구현되어 있었으나(`AutoBidCommandService.createAutoBid`/`updateAutoBid`), 이 문서의 `Proxy integration boundary` 절과 Springdoc/DTO 주석에 "#41 시점, 항상 false/null/false" 문구가 #43 시점까지 남아있었다 — #43에서 문서/Springdoc만 정정했다(코드 변경 없음) | — |
| `AutoBidSetting` 재등록 스키마 제약 | **RESOLVED (#41)**, 단 migration limitation 있음 | 아래 `#41 Implementation Notes` 참고 | — |

## Not Implemented Yet

계약은 확정돼 있지만 endpoint 자체가 아직 없다. 이 상태는 freeze를 막지 않는다.

| # | Endpoint | 비고 |
| --- | --- | --- |
| 10 | GET /auctions/{id}/result | — |
| 11 | POST /auctions/{id}/award/forfeit | — |
| 12 | GET /orders/{id} | Order 패키지 자체가 없음 |
| 13 | POST /orders/{id}/pay | — |
| 14 | GET /me/penalties | `User.isBidRestricted()` 내부 로직만 존재, 조회 API 없음 |
| 15 | GET /backup-offers/{id} | BackupOffer 패키지 없음 |
| 16 | POST /backup-offers/{id}/accept | — |
| 17 | POST /backup-offers/{id}/decline | — |

#18(Similar)/#19(POST likes)/#20(DELETE likes)는 #55에서 구현 완료 — 아래 `#55 Implementation
Notes` 참고.

## Endpoint Status Summary

```text
endpoint implemented (엔드포인트 존재 여부 기준, contract 완전 일치를 의미하지 않음): 12/20
  (#1, #2, #3, #4, #5, #6, #7, #8, #9, #18, #19, #20)
not implemented yet: 8/20 (#10, #11, #12, #13, #14, #15, #16, #17)
implementation gaps (contract resolved, code lagging): #1, #9 — 위 §Deferred Implementation Gaps 참고
  (#1은 product.name/subName·seller.completedSalesCount 두 필드만 남은 gap — 아래
   #55 Implementation Notes 참고. #3/#18/#19/#20은 #55에서 gap 없이 구현됨)
  (#4, #5, #6, #7, #8은 각각 #40/#41에서 확정한 정책을 그대로 구현해 gap 없음 — §40/§41 Implementation Notes 참고)
  (#2/#9의 extensionCount·maxExtensions와 #9의 BID_NOT_ALIGNED는 #43에서 해소. #2의 마지막
   남은 gap이던 numeric error mapping(40402)도 #46에서 AuctionNotFoundException을 40401로
   옮기며 해소되어, #2는 IMPLEMENTED, no gap이다 — #46 Implementation Notes 참고)
contract conflicts: 0/20
```

`12/20`은 endpoint가 존재하는지만 세는 카운트다 — 그 endpoint가 계약 전 필드를 충족한다는
뜻이 아니다. 특히 #1 `GET /auctions/{id}`는 field-level 상태가 아래처럼 갈려 endpoint 자체
상태를 **`IMPLEMENTED WITH DEFERRED GAPS`**로 기록한다(`IMPLEMENTATION_GAP`과 동일 범주,
"완전 MATCH"가 아님을 명확히 하기 위한 표기).

구현된 endpoint들이 `CONTRACT_CONFLICT`가 아니라 `IMPLEMENTATION_GAP`인 이유는 각각의
차이가 "계약이 두 가지로 갈려서"가 아니라 "계약은 하나로 정해져 있는데 코드가 아직 그
계약을 안 지켜서(또는 #2처럼 일부 필드가 구조적으로 아직 불가능해서)" 발생하기 때문이다
(§7 기준 재정리).

## #40 Implementation Notes

`GET /auctions/{id}/live`(#2)와 `GET /auctions/{id}/auto-bid/recommendation`(#4)을 이번
#40에서 구현했다.

```text
#2 /live       — IMPLEMENTED WITH DEFERRED GAPS (당시: endsAt, extensionCount, maxExtensions 미충족.
                 #41 후속에서 endsAt, #43에서 extensionCount/maxExtensions 해소 — 아래 필드별 상태 참고)
#4 /recommendation — IMPLEMENTED, no gap (fallback-only 정책 자체가 계약)
```

#2는 필드별로 다음과 같이 갈린다 — endpoint 전체를 "계약 충족"으로 표현하지 않는다.

```text
/live 필드별 상태
  auctionId/status/currentPrice/minNextBidAmount/bidIncrement       MATCH
  highestBidderMasked/isMine                                        MATCH
  canBid/cannotBidReason/bidRestrictedUntil                         MATCH
  myAutoBidStatus/myCap/minCapAmount                                MATCH
  serverTime (Instant, 응답 생성 시점에 새로 생성 — 저장된 timestamp의
              timezone 미확정 문제와 무관)                            MATCH
  endsAt (TimePolicy로 +09:00 절대시각 변환, Time Policy(#41 후속) 참고)  MATCH
  extensionCount / maxExtensions (#43, Auction.extensionCount/MAX_EXTENSIONS) MATCH
```

`/auto-bid/recommendation`(#4)은 gap 없이 계약과 일치한다 — `aiRecommendedCap`이 항상
`minCapAmount`와 같은 것은 미구현이 아니라 §4에 명시된 fallback 정책 자체가 그렇다
(buyer 전용 AI 추천 소스가 도메인에 없음, `Product`의 판매가 추천값은 seller-side라 재사용
대상이 아님).

## #41 Implementation Notes

`POST /auctions/{id}/auto-bids`(#5), `GET /auctions/{id}/auto-bids/me`(#6),
`PATCH /auctions/{id}/auto-bids/me`(#7), `DELETE /auctions/{id}/auto-bids/me`(#8)를 이번
#41에서 구현했다. Proxy Bidding engine 자체는 이번에도 구현하지 않는다.

### AutoBidSetting 재등록 스키마 제약 — RESOLVED

`auto_bid_settings`에 nullable `active_slot`(Boolean) 컬럼을 추가하고 unique 제약을
`UNIQUE(auction_id, user_id)` → `UNIQUE(auction_id, user_id, active_slot)`로 교체했다.
`RESERVED`/`ACTIVE`/`CAP_REACHED`는 `activeSlot=true`, `CANCELED`는 `activeSlot=null`이며
MySQL이 NULL을 서로 다른 값으로 취급하는 성질을 이용해 "CANCELED 이력은 여러 건, 현재
설정은 최대 1건"을 DB 레벨에서 보장한다. `status`/`activeSlot`은 `AutoBidSetting` 도메인
메서드(`reserve/activate/markCapReached/reactivateAfterCapIncrease/cancel`) 안에서만 함께
바뀐다 — Service가 둘을 개별적으로 건드리지 않는다.

**Migration limitation**: `ddl-auto: update`는 기존 UNIQUE 제약의 DROP/교체를 안정적으로
보장하지 않는다. 이미 뜬 적이 있는 공유 dev/local MySQL에 옛 `uk_auto_bid_setting_auction_user`
제약이 남아있을 수 있으며, 이 경우 수동으로 해당 인덱스를 확인/정리해야 한다. Testcontainers/
CI는 매번 새 스키마이므로 영향 없다.

**동시성 검증**: `AutoBidConcurrencyMySqlIT`(Testcontainers MySQL, InnoDB)로 같은
(경매, 사용자)에 서로 다른 Idempotency-Key로 동시 `POST`를 보내는 케이스를 검증했다 —
정확히 하나만 `201`, 다른 하나는 raw DB 예외가 새지 않고 `409/40908`로 응답하며, 최종
current row는 1개만 남는다.

### Idempotency exact replay — RESOLVED (CREATE_AUTO_BID/UPDATE_AUTO_BID 범위)

`PLACE_BID`의 기존 replay(`resultBidId` 기반, #32)는 변경하지 않았다. `Idempotency`에
nullable `response_snapshot`(TEXT) 컬럼을 추가하고, `IdempotencyClaimService`에 제네릭
`claimAndExecute`/`resolveAfterConflict`(커맨드 executor + 응답 타입 기반)를 추가해
`CREATE_AUTO_BID`/`UPDATE_AUTO_BID`가 재사용한다. 최초 성공 응답을 `ObjectMapper`로 JSON
직렬화해 저장하고, replay는 그 스냅샷을 역직렬화해 반환한다 — 커맨드를 다시 실행하지 않고,
그사이 `Auction.currentPrice`가 바뀌어도 replay 응답은 최초 성공 시점 값을 그대로 유지한다
(`AutoBidServiceTest`로 검증).

### Proxy integration boundary — #41 시점 기록, #42에서 해소됨

(#41 당시 기록, 역사적 참고용) LIVE 등록/수정 응답의 `bidOccurred`/`resultingBidAmount`/
`isHighestBidder`는 이 시점엔 항상 `false`/`null`/`false`였다 — Proxy engine이 아직 없어
실제 가격 경쟁 결과를 계산하지 않았기 때문이다.

**#42에서 `ProxyPriceEngine`이 실제로 구현되며 이 경계는 사라졌다** —
`AutoBidCommandService.createAutoBid`/`updateAutoBid`는 이제 실제 resolution 결과로
`bidOccurred`/`resultingBidAmount`/`isHighestBidder`를 채운다. `CAP_REACHED`에서 cap을
올려도 실제로 경쟁에서 이겨야만(`bidOccurred=true`) `ACTIVE`로 복귀한다 - 단순 cap 상향만으로는
복귀하지 않는다. 다만 이 문서와 Springdoc/DTO 주석에는 #43 시점까지 "#41 당시, 항상
false/null/false" 문구가 정리되지 않고 남아있었다 - #43에서 문서/Springdoc만 정정했다(코드는
#42에서 이미 완료된 상태였음).

### startsAt — endsAt과 동일 gap 공유

`GET /auto-bids/me`의 `startsAt`, `POST /auto-bids`의 `startsAt`은 `Auction.startAt`
(`LocalDateTime`)을 그대로 사용한다 — `/live.endsAt`과 동일한 전역 timezone 미확정 gap을
공유한다(위 `Time 직렬화` 항목 참고). `serverTime`(GET 응답)은 #40과 동일하게 `Instant`로
구현해 계약을 충족한다.

### PATCH validation precedence 확정

`newMaxAmount < minCapAmount`(`40906`)를 공통으로 먼저 확인하고, 그다음 `ACTIVE`/
`CAP_REACHED`에서만 `newMaxAmount <= oldMaxAmount`(`40907`)를 확인한다 — 두 조건을 동시에
위반하면 `40906`이 우선한다. `RESERVED`는 `minCapAmount` 하한만 적용하고 상향/하향/동일값을
모두 허용한다. `AutoBidCommandServiceTest`에 이 precedence를 고정하는 테스트가 있다.

### DELETE 재요청

재요청 시(이미 `CANCELED`, 즉 현재 설정 없음) `404/40404 AUTO_BID_NOT_FOUND`로 응답한다 —
명세 §8에 이미 그 실패 예시가 있어 별도 성공-멱등 처리를 만들지 않았다.

## Time Policy (#41 후속)

FINAL contract가 요구하는 `2026-08-17T20:00:00+09:00` 형태의 절대시각을 다음 정책으로 확정했다.

```text
- DB 컬럼 타입은 그대로(LocalDateTime) - migration 없음.
- 애플리케이션 기준 timezone은 Asia/Seoul로 고정(ClockConfig.APP_ZONE).
- 시간이 필요한 서비스(penalty 판정, serverTime 등)는 LocalDateTime.now()/Instant.now()를
  직접 호출하지 않고 주입된 Clock을 쓴다. production Clock 빈은 Clock.system(Asia/Seoul).
  테스트는 같은 Clock 타입을 Clock.fixed(...)로 교체한다(TestClockConfig, production에서는
  Clock.fixed를 쓰지 않는다).
- API 응답 DTO의 시간 필드(startsAt/endsAt/serverTime/canceledAt/bidRestrictedUntil)는
  OffsetDateTime 타입으로 통일했다. 저장된 LocalDateTime → OffsetDateTime 변환은
  common/util/TimePolicy.toApiTime() 한 곳에서만 한다(LocalDateTime.atZone(Asia/Seoul)).
- paymentDeadline/BackupOffer.deadline/paidAt 등 아직 구현하지 않은 필드도 같은
  TimePolicy를 재사용하면 된다(신규 정책을 또 만들지 않는다).
```

**Jackson 함정과 수정**: jackson-datatype-jsr310은 `OffsetDateTime`을 역직렬화할 때 기본적으로
ObjectMapper의 timezone에 맞춰 오프셋을 재조정한다(`ADJUST_DATES_TO_CONTEXT_TIME_ZONE`). 이
timezone을 앱 정책과 맞추지 않으면(기본값 UTC) Idempotency `response_snapshot`을 JSON으로
왕복시킨 replay 응답의 시간 필드가 `+09:00`이 아니라 `Z`로 조용히 바뀐다 — 같은 instant인데
표기가 달라 최초 응답과도 값이 달라 보이고, FINAL contract의 `+09:00` 요구도 깨진다.
`config/JacksonConfig.java`(`Jackson2ObjectMapperBuilderCustomizer`)로 애플리케이션의
공유 `ObjectMapper` 빈 timezone을 Asia/Seoul로 맞춰 해결했다 — replay 경로에서 실제로
회귀 테스트(`AutoBidServiceTest`)로 확인했다.

## Proxy Bidding 실제 구현 (#41 후속)

이전까지는 §0.13에 정책만 문서화하고 실제 계산은 하지 않는 stub이었다. 이번에 실제 가격
결정 로직(`autobid/service/ProxyPriceEngine.java`)을 구현했다 — Proxy Bidding "엔진 자체를
구현"한 것은 맞지만, 범위는 다음 두 트리거로 한정했다(§0.13에서 이미 확정된 정책의 코드화):

```text
1. AutoBid 등록/수정(LIVE)이 트리거 - resolveForAutoBidEntrant()
2. Manual Bid 성공 직후의 즉시 반격 - resolveAfterManualBid()
```

두 메서드는 같은 pairwise 비교 로직(effectiveCap 계산 + FIRST-IN WINS tie-break)을
공유한다. **경쟁자 판정은 "다른 사용자의 ACTIVE AutoBid" 여부만으로 하지 않는다** —
`Auction.currentWinner`/`currentPrice`가 항상 competitor 판정의 기준이다.

```text
currentWinner == null                              → 경쟁자 없음, 가격 그대로
currentWinner == entrant 본인                        → 자기 자신과 경쟁하지 않음
currentWinner != entrant, 그 사용자에 ACTIVE AutoBid 없음 → ceiling = 현재 currentPrice(manual-only, 더 늘어나지 않음)
그 외(다른 사용자의 ACTIVE AutoBid가 auction 전체에서 발견됨) → 그 effectiveCap이 ceiling
```

마지막 케이스는 "recorded currentWinner가 보유한 AutoBid"로 한정하지 않고, entrant를
제외한 auction 전체의 ACTIVE 중 최고 effectiveCap을 찾는다(동률이면 `createdAt` 빠른 쪽,
그마저 같으면 `id` 빠른 쪽).

**복수 ACTIVE dirty data 처리 방침**: #41 초판(Proxy 미구현 기간)에 등록된 LIVE AutoBid는
경쟁 없이 무조건 ACTIVE로 저장됐으므로, 한 경매에 여러 명이 동시에 ACTIVE로 남아있는
상태가 이미 만들어졌을 수 있다. 정상 상태라면 "경쟁 가능한 incumbent AutoBid는 최대 1개만
ACTIVE"여야 한다. 두 방식(①과거 데이터를 스캔해 즉시 정리 vs ②resolution마다 자연 정상화)
중 **②를 선택했다** — 매 resolution이 "entrant를 제외한 전체 ACTIVE 중 objectively 가장 강한
경쟁자"를 다시 찾기 때문에, dirty 상태라도 그 순간 관여하는 두 참가자(entrant, 최강
경쟁자)는 항상 올바르게 정리된다(패배자→`CAP_REACHED`). 다만 이번 resolution에 관여하지
않은 다른 dirty ACTIVE row(예: 최강 경쟁자보다도 약한 제3의 row)는 그 자리에서 정리되지
않고, 그 row 자신이 나중에 entrant가 되거나 다시 조회 대상이 될 때 점진적으로 정리된다 —
전체를 즉시 일괄 정리하는 배치 작업은 이번 범위가 아니다. "잘못된 winner가 나오지 않는다"는
`ProxyPriceEngineTest#복수_ACTIVE_dirty_data_상태에서도_가장_강한_경쟁자_기준으로_정상_판정된다`로
검증했다.

## `/bids/{auctionId}` PLACE_BID 확장 (#41 후속)

`PlaceBidResponse`를 FINAL contract shape(`submittedAmount`/`currentPrice`/
`minNextBidAmount`/`highestBidderMasked`/`isHighestBidder`/`autoBidCanceled`/
`proxyResponded`/`endsAt`)로 전면 재작성했다. `extensionCount`만 여전히 gap이다(위 표 참고).

- **`autoBidCanceled`**: `BidCommandService.placeManualBid()`가 기존 Manual Bid validation
  (상태/판매자/최고입찰자/최소금액)을 **전부 통과한 뒤에만** 요청자의 ACTIVE/CAP_REACHED
  `AutoBidSetting`을 `cancel()`한다 — validation 실패는 예외를 던지고 트랜잭션 전체가
  롤백되므로, 실패한 직접입찰 때문에 기존 AutoBid가 취소되는 일은 없다
  (`BidCommandServiceTest#검증에_실패한_직접입찰은_기존_AutoBid을_취소하지_않는다`로 검증).
- **`proxyResponded`**: Manual Bid가 실제로 반영된 뒤 `ProxyPriceEngine.resolveAfterManualBid()`로
  다른 사용자의 경쟁 AutoBid가 즉시 반격하는지 확인한다. AutoBid-vs-AutoBid와 같은 엔진을
  공유하므로 별도의 가격 알고리즘을 중복 구현하지 않았다.
- Manual Bid와 Proxy counter 모두 기존 `findByIdForUpdate` 트랜잭션(Pessimistic Lock) 안에서
  처리된다 - 이 부분의 락 구조 자체는 변경하지 않았다.

**Idempotency exact replay 확장**: PLACE_BID도 #41의 제네릭 `response_snapshot` 메커니즘으로
옮겼다 — AutoBid cancel + Manual bid + Proxy counter가 모두 끝난 최종 응답을 스냅샷으로
저장하고, replay는 그 시점 Auction 상태를 다시 읽지 않고 스냅샷을 그대로 반환한다
(`AutoBidServiceTest`와 동일한 패턴을 `BidCommandServiceTest`/`ManualBidServiceTest`에도
적용). 기존 PLACE_BID 전용 `claimAndPlaceBid`/`resolveAfterConflict`(`resultBidId` 기반)는
완전히 미사용이 되어 제거했다 — 삭제 전 기존 메서드가 보장하던 트랜잭션 경계/UNIQUE 충돌 후
별도 트랜잭션 조회/rollback 동작이 제네릭 버전에서도 동일한지 `ManualBidIdempotencyMySqlIT`
(실제 MySQL, 동시 same-key 요청)로 회귀 검증했다 — 그대로 통과했다.

## Active-slot UNIQUE Migration Limitation — 실측 확인 (#41 후속)

`docs/api/auction-api-contract-gap.md`(#41 원본)에 "ddl-auto:update가 기존 UNIQUE 제약
DROP을 보장하지 않는다"를 이론적 우려로 적어뒀는데, 이번에 `AutoBidSettingSchemaMigrationIT`
(Testcontainers MySQL)로 **실제로 재현/확인했다**:

```text
1. @BeforeAll에서 raw JDBC로 #40/#41 이전 스키마(UNIQUE(auction_id, user_id)만 있는
   auto_bid_settings 테이블)를 미리 만들어둔다.
2. 그 위에서 Spring context가 뜨며 ddl-auto:update가 실행된다(현재 엔티티 기준).
3. information_schema.TABLE_CONSTRAINTS로 실제 남은 제약을 조회한다.

관찰 결과:
  uk_auto_bid_setting_auction_user (구)  → 그대로 남음
  uk_auto_bid_setting_active_slot  (신)  → 추가로 생성됨
  → 두 제약이 동시에 존재한다.
```

**실제 기능 영향도 확인**: 이 상태에서 같은 (auction, user)로 CANCELED row를 2건 저장하면
신규 제약(activeSlot 다름)은 통과하지만 **구 제약(auction_id+user_id만) 때문에 여전히
`DataIntegrityViolationException`이 발생한다** — #41 재등록 정책이 스키마 레벨에서 다시
막히는 실제 회귀다. 이 결과도 같은 IT의 두 번째 테스트로 확인했다.

**공유 dev/local DB 수동 정리 SQL** (이미 #40/#41 이전 스키마로 떠 있던 MySQL에 한해 실행):

```sql
ALTER TABLE auto_bid_settings DROP INDEX uk_auto_bid_setting_auction_user;
```

`information_schema.TABLE_CONSTRAINTS`로 실행 전/후 제약 목록을 확인하는 것을 권장한다.
Testcontainers/CI로 매번 새로 뜨는 스키마는 애초에 구 제약이 존재한 적이 없으므로 영향 없다.
이 문서 작성 시점 기준 이 프로젝트에 실제로 연결 가능한 공유 dev/local MySQL이 없어(로컬
Docker에 떠 있는 MySQL 컨테이너는 이 프로젝트와 무관한 별도 프로젝트의 것이었다) 위 SQL을
실제 운영 DB에 적용하지는 않았다 — 적용이 필요해지면 이 SQL을 그대로 실행하면 된다.

## #43 Implementation Notes

이번 #43에서는 `ProxyPriceEngine`/idempotency 구조를 재구현하지 않고, #41~#42 이후 남아있던
gap만 최소 수정했다.

### Direct bid alignment — RESOLVED

`Auction.placeManualBid()`가 기존 최소금액 검증(`amount < minNextBidAmount` → `40904`)을
통과한 값에 한해 `(amount - currentPrice) % bidIncrement != 0`을 확인해 `BidNotAlignedException`
(`40913 BID_NOT_ALIGNED`)을 던진다. validation precedence는 기존 seller/penalty/status/
highest-bidder 순서를 그대로 유지하고, 그 뒤 min-check → alignment-check 순으로 배치했다 —
min 미만이면 배수가 맞아도 `40904`가 우선한다. AutoBid `maxAmount`에는 이 검증을 적용하지
않는다(§5, 실효 상한으로 동작하는 기존 정책 그대로 유지).

### Auction 종료 연장 — RESOLVED

`Auction`에 `extensionCount`(int, 컬럼 기본값 0) 필드와 `MAX_EXTENSIONS=3` 상수를 추가했다.
`Auction.maybeExtend(LocalDateTime now)`가 유일한 진입점이다 — `extensionCount < MAX_EXTENSIONS`
이고 `now`가 `endAt`으로부터 1분 이내(경계값 포함)면 `endAt`을 3분 연장하고 `extensionCount`를
1 증가시킨다. 멱등하지 않다 - 호출자가 "성공한 사용자 command당 최대 1회"를 보장해야 한다.

트리거 조건은 "가격/승자 변동 여부"가 아니라 **"해당 사용자 command로 실제 Bid가
발생했는가"**로 확정했다(§0.13 참고, 사용자 확정 사항):

```text
BidCommandService.placeManualBid()      — 검증/Proxy resolution이 모두 끝난 뒤 무조건 1회 호출
                                           (Manual Bid 성공은 항상 실제 MANUAL Bid를 만들어낸다)
AutoBidCommandService.createAutoBid()   — LIVE이고 bidOccurred=true일 때만 호출
AutoBidCommandService.updateAutoBid()   — LIVE이고 bidOccurred=true일 때만 호출
```

RESERVED(SCHEDULED) 등록/수정, Proxy 내부에서 파생되는 AUTO Bid, GET/DELETE, scheduler/
lifecycle은 대상이 아니다 — 애초에 위 세 호출 지점 바깥이라 자연스럽게 제외된다.

`/live`의 `extensionCount`/`maxExtensions`(`AuctionQueryService`), `POST /bids`의
`extensionCount`(`BidCommandService`)를 실제 도메인 값으로 연결했다. `AutoBid POST/PATCH`
응답에는 계약상 `extensionCount` 필드가 없어(§5/§7) 추가하지 않았다 — 연장 여부는 다음
`GET /live` 호출에서 확인된다.

### Response gap 정리 — RESOLVED

`AutoBidCommandService.createAutoBid`/`updateAutoBid`의 `bidOccurred`/`resultingBidAmount`/
`isHighestBidder`는 #42에서 이미 `ProxyPriceEngine` 실제 결과를 반환하도록 구현되어 있었다
(코드 변경 없음, 회귀 테스트로 재확인만 했다). `AutoBidUpdateResponse`의 DTO 주석과
`AuctionController`의 Springdoc 설명에 남아있던 "#41 시점, 항상 false/null/false" 문구를
정정했다(위 `Proxy integration boundary` 절 참고). `POST /bids`의 Springdoc `@ApiResponses`에
누락돼 있던 `40913`도 추가했다.

### Idempotency exact replay — 구조 변경 없음, 회귀만 추가

`ManualBidServiceTest`에 "최초 성공 이후 currentPrice/extensionCount/endAt이 모두 바뀌어도
same key + same payload retry는 최초 response_snapshot을 그대로 반환한다" 회귀 테스트를
추가했다(`AutoBidServiceTest`에는 #41 후속에서 이미 currentPrice 기준 동일 패턴이 있었다).
같은 key + 다른 payload는 기존 `40905` 그대로다 — 변경 없음.

## #44 Implementation Notes

새 기능/production 코드 변경 없이, #40~#43에서 이미 확정·구현된 핵심 정책을 회귀 불변식
테스트로 고정했다. 기존 테스트 커버리지를 먼저 전수 조사해 이미 충분히 검증되는 항목은
중복 테스트를 만들지 않았고, 실제 빠져있던 3건만 추가했다:

```text
1. CANCELED AutoBid가 가격 계산에서 실제로 제외되는지(서비스 레벨) — Manual/AutoBid 양쪽
2. EffectiveCapCalculator 전용 단위 테스트(비정렬/경계값 - 이전엔 간접 커버만 있었음)
3. CREATE_AUTO_BID의 같은 key 동시 요청 시 row 1건만 생성되는지(실제 MySQL) —
   PLACE_BID는 이미 있었으나 AutoBid 쪽엔 없었다(기존 AutoBidConcurrencyMySqlIT는
   "서로 다른 key" 동시 요청만 검증 - 그건 idempotency가 아니라 active-slot UNIQUE 레이어)
```

### DEFERRED UNTIL LIFECYCLE INTEGRATION

`ProxyPriceEngine`의 trigger=None(경매 시작 시 RESERVED 일괄 정산) 순수 계산 자체는
`ProxyPriceEngineTest.트리거없는_정산`에 이미 충분히 커버되어 있다(0/1/2명 예약자,
FIRST-IN WINS, cap 차등 케이스). 여기서 검증하지 않은 것은 **"실제 `SCHEDULED → LIVE`
lifecycle을 통해 이 trigger=None 경로가 실제로 호출되는 production 진입점"**이다 —
이 프로젝트에 경매 시작을 자동으로 트리거하는 scheduler/lifecycle 코드가 아직 없기 때문에
(§Not Implemented Yet 참고), 이 통합 테스트를 지금 추가하면 존재하지 않는 production
호출부를 테스트만을 위해 억지로 만들게 된다 — 하지 않았다.

**lifecycle 병합 후 활성화할 테스트 조건**:

```text
- lifecycle/scheduler가 SCHEDULED → LIVE 전환 시점에 ProxyPriceEngine.resolve(trigger=None)을
  실제로 호출하는 production 코드(가칭 AuctionLifecycleService 등)가 병합되면,
- 그 호출부를 대상으로 "RESERVED 1명/2명 이상 시나리오에서 lifecycle 호출 결과가
  ProxyPriceEngineTest의 순수 계산 결과와 일치하는지"를 서비스/DB 레벨 테스트로 추가한다.
- 최소 케이스: 예약자 0명(가격 불변), 1명(최소 한 단계 응찰), 2명 이상(최강/차강 기준
  가격 결정 + FIRST-IN WINS)을 실제 Auction.start() 이후 상태(currentPrice/currentWinner/
  AutoBidSetting.status/영속 Bid)로 검증한다.
- 이 항목이 이번 #44 범위에서 빠진 이유가 "trigger=None 계산이 미검증"이 아니라
  "production 호출부 자체가 아직 없음"이라는 점을 테스트 추가 시 주석으로 남긴다.
```

### 확인했으나 변경하지 않은 것

기존 테스트에 이미 충분히 커버되어 추가하지 않은 항목(중복 방지) — Proxy 가격 monotonic,
winner effectiveCap/maxAmount 초과 금지, FIRST-IN WINS(동률), manual이 모든 cap보다 크면
manual 승리, AutoBid의 즉시 반격(proxyResponded), 비정렬 direct bid(`40913`),
`priceChanged=false + winnerChanged=true` 동률 케이스의 AUTO Bid persistence/currentWinner
일치, AutoBid 상태 전이 전체(`CAP_REACHED` 진입/복귀, `RESERVED`/`ACTIVE`/`CAP_REACHED`
cap 변경 규칙, `CANCELED` terminal, 재등록, active-slot UNIQUE), 종료 연장 전체(#43에서
이미 추가), Idempotency exact replay/40905/PLACE_BID 동시성. 상세 커버리지 매핑은 이
브랜치의 테스트 리뷰 기록 참고.

## #45 Implementation Notes

Proxy/Manual/AutoBid 가격 쓰기 경로의 Pessimistic Lock 커버리지를 감사하고, Manual/AutoBid
혼합 동시 요청을 실제 MySQL로 검증하고, 가격/승자 resolution의 최소 audit log를 추가했다.
`ProxyPriceEngine` 재설계, Event Sourcing, 새 lock 전략/transaction 구조 재설계는 하지 않았다.

### Lock coverage audit — 결과: 최상위 Auction row는 문제 없음, 2차 쿼리에서 실제 gap 발견

세 가격 쓰기 경로(Manual Bid, LIVE AutoBid CREATE, LIVE AutoBid UPDATE) 모두 최초
authoritative read가 이미 `AuctionRepository.findByIdForUpdate()`(`PESSIMISTIC_WRITE`)였다 -
이 부분은 gap이 없었다.

**하지만 실제 MySQL 혼합 동시성 테스트로 별도의 실제 gap을 발견했다**:
`AutoBidSettingRepository.findByAuctionIdAndStatusAndUserIdNot()`(Proxy가 "경쟁자 후보"를
찾는 쿼리)가 일반(락 없는) SELECT였다. 원인은 다음과 같다.

```text
1. IdempotencyClaimService.claimAndExecute()의 claim 조회, 커맨드 안의 User 조회 등 -
   command 실행 이전의 일반(non-locking) 조회가 이미 이 트랜잭션의 REPEATABLE READ
   snapshot을 만들어 버릴 수 있다.
2. Auction의 findByIdForUpdate()는 locking read라 snapshot과 무관하게 항상 최신 커밋을
   본다 - 여기까지는 문제가 없다.
3. 그 뒤에 나가는 competitor AutoBidSetting 조회는 잠금이 없는 일반 SELECT라, 1번에서 이미
   만들어진 snapshot을 그대로 쓴다 - 그사이 동시에 commit된 다른 사용자의 AutoBidSetting을
   놓칠 수 있다(stale candidate set).
4. 즉 Auction row lock을 확보했다는 사실만으로는 "Proxy가 보는 경쟁자 후보 목록이 최신인지"
   까지 보장하지 못했다.
```

**실제 재현**: 서로 다른 3명이 같은 LIVE 경매에 동시에 AutoBid를 CREATE하면, 최강 cap 1명만
ACTIVE·나머지 CAP_REACHED가 되어야 하는데 **3명 전원이 ACTIVE로 남는** 것으로 재현됐다
(`ProxyMixedConcurrencyMySqlIT`). Manual Bid와 AutoBid CREATE의 2자 혼합 시나리오는 우연히
같은 문제를 피해갔다 - Manual 쪽은 `auction.getCurrentWinner()`를 이미 락이 걸린 Auction
read에서 가져오기 때문이다. 이 우연한 회피는 실행 순서에 따라 달라질 수 있어 신뢰할 수 없다.

**적용한 수정**: `findByAuctionIdAndStatusAndUserIdNot()`에 `@Lock(PESSIMISTIC_READ)`가
아니라 **`@Lock(PESSIMISTIC_WRITE)`**를 적용했다 - competitor AutoBidSetting은 Proxy
resolution 이후 실제로 상태가 바뀌는(ACTIVE↔CAP_REACHED) 대상이므로 단순 최신 조회 목적의
READ 락보다 WRITE 락이 적절하다는 판단이다. 데드락 위험은 없다 - 이 row를 쓸 수 있는 유일한
경로가 같은 Auction row의 write lock을 먼저 획득한 트랜잭션뿐이라, 이 시점에 경쟁할 수 있는
다른 살아있는 트랜잭션이 없다.

**Lock ordering 확인**: 세 경로 모두 `Auction FOR UPDATE → (필요시 User/existing-check 등
비잠금 조회) → competitor AutoBidSetting FOR UPDATE → Proxy resolution → Auction/Bid/
AutoBidSetting 변경 → audit → commit` 순서를 지킨다 - 어떤 경로도 AutoBidSetting 락을
Auction 락보다 먼저 획득하지 않는다(교차 lock ordering으로 인한 deadlock 위험 없음).
`AutoBidCommandService.updateAutoBid()`는 entrant 자신의 기존 설정을 찾는
`findByAuctionIdAndUserIdAndActiveSlotTrue()`를 Auction 락 획득보다 먼저 호출하지만, 이
호출 자체는 락을 전혀 잡지 않는 조회라 lock ordering 문제가 아니다(추가 분석 결과 이 조회가
stale해도 뒤이은 `changeMaxAmount()`가 절대값을 그대로 덮어써 실제 데이터 corruption으로
이어지지 않는다는 것도 확인했다 - 이번 범위에서 추가로 잠그지 않았다).

**재검증 결과(수정 후, 실제 MySQL)**: 3명 동시 LIVE AutoBid CREATE(최강 1명 ACTIVE·나머지
CAP_REACHED로 정상화), Manual+AutoBid CREATE, Manual+AutoBid UPDATE, `AutoBidConcurrencyMySqlIT`/
`ManualBidIdempotencyMySqlIT`(기존 테스트) 전부 재실행해 통과 확인.

### SYSTEM_OPEN(시작 정산) lock 대상 — DEFERRED UNTIL LIFECYCLE INTEGRATION 유지

`ProxyTrigger.None`(경매 시작 시 RESERVED 일괄 정산)은 여전히 production 호출부가 없다
(#44에서 이미 DEFERRED로 기록). lifecycle이 병합되면 그 호출부도 **동일한 lock audit
대상**에 포함해야 한다 - 특히 이번에 발견한 "Auction 락만으로는 관련 AutoBidSetting 후보
visibility가 보장되지 않는다"는 교훈이 그대로 적용된다(RESERVED 일괄 정산도 여러
AutoBidSetting을 동시에 읽고 판정하므로 같은 stale-snapshot 위험이 있다).

### 실제 MySQL 혼합 동시성 검증 — RESOLVED

`ProxyMixedConcurrencyMySqlIT`(신규, `concurrency` 패키지)에 Manual+AutoBid CREATE,
Manual+AutoBid cap UPDATE, 복수 사용자 AutoBid 경쟁 3개 시나리오를 추가했다. 기존
`AutoBidConcurrencyMySqlIT`/`ManualBidIdempotencyMySqlIT`의 harness(TestRestTemplate +
CountDownLatch + ExecutorService)를 그대로 재사용했다. 응답 개수가 아니라 트랜잭션 종료 후
DB post-state(currentPrice monotonic, winner↔영속 Bid 일치, winner AutoBid effectiveCap
초과 금지)를 재조회해서 검증한다 - 실행 순서가 nondeterministic하므로 단일 고정값을
assert하지 않는다. 순수 Proxy invariant(`ProxyPriceEngineTest`)는 여기서 중복 재작성하지
않았다.

### Lock timeout → 40909 — RESOLVED

`org.springframework.dao.PessimisticLockingFailureException`(`CannotAcquireLockException`/
`DeadlockLoserDataAccessException`의 공통 부모)을 `GlobalExceptionHandler`에 매핑했다 -
일반 `DataAccessException`까지 넓히지 않았다. `AuctionLockTimeoutMySqlIT`(신규)가
`--innodb-lock-wait-timeout=2`로 짧게 설정한 실제 MySQL 컨테이너에서, 한 트랜잭션이
`TransactionTemplate`으로 Auction row lock을 보유한 채 다른 사용자의 Manual Bid를 실행해
lock 대기 타임아웃을 재현하고, 그 경로가 500이 아니라 `409/40909`로 응답하는지 확인한다.

### 최소 Auction price audit log — RESOLVED

Event Sourcing이 아니라, 가격/승자 resolution의 원인만 추적하는 최소 엔티티
`AuctionPriceAudit`(`auction_price_audits` 테이블, `auction.audit` 패키지)를 추가했다.

```text
auction_id, before_price, after_price, resulting_winner_id,
trigger_type (enum: MANUAL_BID/AUTO_BID_CREATE/AUTO_BID_UPDATE/SYSTEM_OPEN),
bid_type (enum: MANUAL/AUTO, 기존 BidType 재사용),
applied_rule (enum: MANUAL_UNCONTESTED/MANUAL_OVERTAKEN_BY_AUTO/TIE_FIRST_IN_WINS/
              AUTO_ENTRANT_WINS/AUTO_INCUMBENT_DEFENDS),
idempotency_id (nullable, FK 참조값만 - Idempotency 엔티티와 연관관계는 맺지 않는다),
created_at
```

컬럼명은 `trigger`가 아니라 **`trigger_type`**이다 - `trigger`는 MySQL 8 예약어라 DDL/INSERT가
구문 오류로 실패하는 것을 실제로 재현한 뒤 이름을 바꿨다(이번에 새로 만든 엔티티 자체의 버그,
사전 존재 문제 아님).

기록 조건은 "커맨드 시작 전 대비 최종 상태가 실제로 바뀌었는가"(`priceChanged || winnerChanged`)
하나다 - Manual Bid는 성공하면 항상 가격이 오르므로 사실상 항상 기록되고, AutoBid CREATE/UPDATE는
LIVE 분기 안에서만 이 조건으로 판단한다. `priceChanged=false && winnerChanged=true`(동률
FIRST-IN WINS) 케이스도 이 조건으로 정확히 기록 대상이 된다 - `TIE_FIRST_IN_WINS`
전용 `appliedRule` 값으로 구분했다. 순수 no-op(경쟁자 없는 LIVE 등록, 자기 자신이 이미
winner인 채 cap만 상향 등)에는 기록하지 않는다.

Writer는 `ProxyPriceEngine`이 아니라 persistence/application boundary(`AuctionPriceAuditRecorder`,
`BidCommandService`/`AutoBidCommandService`가 호출)에 있다 - engine은 이 엔티티의 존재를
전혀 모른다. 한 사용자 command당 최대 1건만 기록되도록(Proxy 내부 파생 응찰이 몇 개든) 호출
지점을 트랜잭션당 1회로 제한했다.

### Idempotency-Key 추적 방식 — raw key 대신 claim row PK 참조로 결정

기존 Idempotency claim/replay/충돌 판정 로직은 전혀 바꾸지 않았다. `IdempotencyClaimService.
claimAndExecute()`의 커맨드 파라미터를 `Supplier<T>`에서 **`Function<Long, T>`**로 바꿔,
claim insert 직후 확보한 `claim.getId()`를 커맨드에 그대로 전달한다 - `ManualBidService`/
`AutoBidService`가 이 값을 `BidCommandService.placeManualBid()`/`AutoBidCommandService.
createAutoBid()`/`updateAutoBid()`의 새 `idempotencyId` 파라미터로 넘기고, 그 값이 그대로
audit row의 `idempotency_id`가 된다. 기존 3개 서비스 메서드는 이 파라미터를 생략하는 오버로드로
남겨 기존 호출부(테스트 포함)를 전혀 바꾸지 않았다(오버로드가 `null`로 위임).

raw `Idempotency-Key` 문자열을 audit에 복제하는 대안도 검토했으나 채택하지 않았다 - PK
참조가 데이터 중복이 없고, `Idempotency` row 자체가 이미 (user, operationScope, key)를
들고 있어 필요하면 그 row에서 역참조할 수 있다. `AuctionPriceAudit`은 `Idempotency`와
JPA 연관관계를 맺지 않고 평범한 `Long` 컬럼으로만 참조한다 - 이 값을 조회에 활용할 필요가
없어서다.

### Transaction atomicity(audit 포함) — RESOLVED

`AuctionPriceAuditAtomicityMySqlIT`(신규)가 #31 스모크 테스트와 같은 방식(임시 `CHECK`
제약으로 강제 실패, production fail hook 없음)을 자동화된 IT로 재사용한다.
`auction_price_audits` 테이블에 항상 위반되는 CHECK 제약을 걸고, Manual Bid/AutoBid
CREATE/AutoBid UPDATE 세 경로 각각을 **실제 경쟁자가 있어 Proxy가 그 경쟁자의 AutoBidSetting
상태까지 바꾸는 시나리오**로 구성해 강제 실패시킨 뒤, 다음이 전부 롤백되는지 확인한다: Auction
가격/승자, 신규 Bid, 경쟁자의 AutoBidSetting 상태, `AuctionPriceAudit` row, Idempotency
claim row(성공 snapshot 포함). 세 경로 모두 audit이 같은 트랜잭션 안에 있음을 이 방식으로
확인했다.

## #46 Implementation Notes

`refactor/#46-write-api-final-contract`에서 4개 write API(POST /bids, POST /auto-bids,
PATCH /auto-bids/me, DELETE /auto-bids/me)의 Controller/DTO/공통 envelope/exception
mapping/Springdoc을 FINAL contract와 최종 대조했다. Envelope·DTO·상태 코드·Idempotency
계약은 이미 #41~#45에서 충족돼 있었고, 이번에 실제로 고친 것은 두 가지뿐이다.

### Numeric error mapping — `AuctionNotFoundException` 40402→40401 RESOLVED

§Deferred Implementation Gaps에 기록돼 있던 `AuctionNotFoundException`의 번호 불일치를
`AuctionNotFoundException`만 단독으로 수정했다. 재확인한 근거:

```text
- 40402(ORDER_NOT_FOUND)는 Order 도메인이 아직 없어 어떤 예외도 점유하고 있지 않았다
  (renumbering 시점 기준 handler/production 코드 전수 검색으로 확인).
- 40401은 이전까지 어떤 handler도 쓰고 있지 않았다.
- 40403(BACKUP_OFFER_NOT_FOUND) 자리를 점유 중인 UserNotFoundException은 이번에 건드리지
  않았다 - BackupOffer 도메인이 아직 없어 지금 당장 충돌이 없고, 이번 이슈가 요청한 범위
  (AuctionNotFoundException 단독)를 벗어나는 별도 renumbering이기 때문이다. 여전히 후속
  issue로 남는다(§Deferred Implementation Gaps 표 참고).
- 40404(AUTO_BID_NOT_FOUND)는 AutoBidNotFoundException이 이미 올바르게 쓰고 있어 영향 없다.
```

변경 파일: `GlobalExceptionHandler.handleAuctionNotFoundException()`(40402→40401)뿐이다 -
`AuctionNotFoundException`을 던지는 지점(`AuctionQueryService`, `BidQueryService`,
`BidCommandService`, `AutoBidCommandService`)은 전부 이 한 handler로 수렴하므로 production
코드는 그 외에 바꿀 곳이 없다. `AuctionControllerTest`의 관련 5개 회귀(경매 상세조회/입찰이력
조회/입찰/`live`조회/자동입찰 추천조회의 "존재하지 않는 경매" 케이스)를 40402→40401로 갱신했다.
이 renumbering으로 §Endpoint Status Summary의 `#2 /live`도 마지막 남은 gap이 해소되어
`IMPLEMENTED, no gap`으로 재분류한다(`#1`은 응답 shape 자체가 여전히 다른 gap이라 재분류하지
않는다).

### Lock 사이 non-locking 조회 — CORRECTION 및 RESOLUTION: "본인 row라서 안전하다"는 틀린 근거였고, 실제 stale-read 버그를 발견해 A안(locking current read)으로 고쳤다

최초 작성 시 아래처럼 결론 냈으나 틀렸다 — 그대로 남겨두면 오해를 재생산하므로 무엇이 틀렸는지와
함께 기록한다.

```text
[틀린 결론, 삭제하지 않고 반례로 남김]
"idempotency claim 조회 / 본인 소유 User·AutoBidSetting 조회는 호출자 본인 행만 보므로
non-locking이어도 안전하다."
```

**왜 틀렸는가**: MySQL/InnoDB REPEATABLE READ의 read view는 트랜잭션의 **"첫 SELECT"** 가 아니라
**"첫 non-locking consistent read"** 에서 확립될 수 있다 — locking read(`FOR UPDATE`)는 read
view 확립에 관여하지 않고 항상 최신 커밋을 읽는다. 이 구조(claim → command)에서는 command
실행 전 `IdempotencyClaimService`의 claim 조회(일반 SELECT)가 사실상 그 트랜잭션의 첫
non-locking consistent read라, 그 지점에서 read view가 고정될 수 있다. 이후 같은 트랜잭션의
모든 non-locking SELECT는 "누구의 row인가"와 무관하게 그 read view를 그대로 쓴다.
`AutoBidSetting`처럼 **읽은 값을 그대로 새 값 계산에 쓰고 절대값으로 덮어쓰는(read-then-overwrite)
mutable 필드**를 이런 non-locking 조회로 읽으면 lost update가 발생할 수 있다 — `User` 조회가
이 문제와 무관한 이유는 "본인 소유라서"가 아니라, 이 커맨드들이 User를 아예 mutate하지 않는
순수 참조 조회이기 때문이다(아래 "참조 조회 vs mutable 조회 구분" 참고).

### 실제 재현 (UPDATE_AUTO_BID) — 발견 당시 CONFIRMED BUG, 이번에 RESOLVED

시나리오: 같은 사용자가 서로 다른 Idempotency-Key로 `cap=100`인 자기 AutoBid에 동시에
`PATCH cap=200`과 `PATCH cap=150`을 보낸다. 정책(§7, `40907 CAP_NOT_INCREASED`)상 cap은
감소해선 안 되므로 최종 cap은 200 밑으로 내려가면 안 된다.

**수정 전** `AutoBidCommandService.updateAutoBid()`의 실제 호출 순서:

```text
1. [Auction 락 이전, non-locking] IdempotencyRepository.findByUserIdAndOperationScope...()
   — 이 SELECT가 트랜잭션의 read view를 확립할 수 있다.
2. [Auction 락 이전, non-locking] AutoBidSettingRepository
   .findByAuctionIdAndUserIdAndActiveSlotTrue(auctionId, userId)
   — entrant 본인 설정을 "Auction 락을 잡기 전에" 읽는다. 1번에서 확립된 read view를 그대로 쓴다.
3. AuctionRepository.findByIdForUpdate(setting.getAuction().getId()) — PESSIMISTIC_WRITE
   (locking read라 read view와 무관하게 최신 Auction을 본다 - 여긴 원래도 문제 없었다)
4. requiresIncrease && newMaxAmount <= setting.getMaxAmount() 검사
   — 여기서 쓰는 setting.getMaxAmount()가 2번에서 읽은, read view 확립 시점의 stale 값이다.
5. setting.changeMaxAmount(newMaxAmount) — 절대값으로 덮어쓰기. @Version 없음(낙관적 락 없음).
```

두 트랜잭션(cap=200, cap=150) 모두 2번을 Auction 락 대기 이전에 실행하므로, 둘 다 서로의 커밋
이전에 이미 자신의 read view(`maxAmount=100`)를 확립한다. Auction 락이 둘을 3번에서
직렬화시켜 어느 한쪽(예: cap=200)이 먼저 커밋해도, 나중에 실행되는 쪽(cap=150)의 4번 검사는
**여전히 자신의 read view(100)** 를 기준으로 `150 <= 100`을 확인하므로 통과해버리고, 5번에서
이미 커밋된 200을 150으로 그대로 덮어쓴다.

**`AutoBidCapUpdateStaleReadMySqlIT`**(당시 신규, `@Tag("experiment")`)로 실제
MySQL(Testcontainers, InnoDB)에서 확인했다:

```text
- 최초 delay 없이 실행 → 우연히 통과(두 HTTP 요청이 자연 지연으로 사실상 순차 실행되어
  레이스 윈도우 자체가 열리지 않음 - 재현 실패를 의미하지 않는다, 강제 실행에서 확인됨).
- ManualBidConcurrencyRaceIT(#35)와 동일한 RaceWindowDelay 패턴(production
  AutoBidSettingRepository를 감싸는 test-only @Primary proxy)으로
  findByAuctionIdAndUserIdAndActiveSlotTrue() 반환 직후 1000ms를 강제 삽입해 두 트랜잭션이
  모두 own-setting read view를 확립한 뒤에야 Auction 락 경쟁을 시작하도록 강제.
- 결과: 두 PATCH 모두 200 OK(어느 쪽도 40907로 거부되지 않음). 최종 커밋된 maxAmount = 150000.
  기대값(>= 200000) 위반 — AssertionError로 확인.
```

#### 적용한 수정 — A안(locking current read), RESOLVED

`AutoBidSettingRepository`에 write 경로 전용 locking finder를 추가했다:

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select s from AutoBidSetting s where s.auction.id = :auctionId and s.user.id = :userId and s.activeSlot = true")
Optional<AutoBidSetting> findCurrentByAuctionIdAndUserIdForUpdate(Long auctionId, Long userId);
```

기존 `findByAuctionIdAndUserIdAndActiveSlotTrue()`(non-locking)는 순수 조회(GET /auto-bids/me
등)에 그대로 남겨뒀다 — write 경로 세 지점만 이 새 locking finder로 교체했다:

```text
- UPDATE_AUTO_BID: updateAutoBid()가 own-setting 조회를 이 locking finder로 바꿨다. 단, 이
  조회는 원래 Auction FOR UPDATE보다 "먼저" 실행됐다 - locking read로 바꾸면서 그 순서를
  유지하면 AutoBidSetting 락을 Auction 락보다 먼저 잡게 되어 #45가 확정한 lock ordering
  (Auction FOR UPDATE → AutoBidSetting FOR UPDATE, 다른 두 경로는 이미 이 순서)과 충돌해 새
  데드락 경로가 생긴다. 그래서 순서 자체를 뒤집었다 - auctionId는 controller/path variable에서
  이미 넘어오므로, own-setting을 거치지 않고도 Auction을 먼저 조회할 수 있다.
  결과: Auction FOR UPDATE(auctionId 직접 사용) → own-setting FOR UPDATE(신규 locking
  finder) → penalty/closed/minCap/40907 검증 → (LIVE라면) competitor FOR UPDATE → Proxy →
  mutation → audit → commit. 세 write 경로 모두 "Auction → own-setting → competitor" 순서로
  통일됐다.
- CREATE_AUTO_BID: createAutoBid()의 40908 사전 존재 검사를 이 locking finder로 바꿨다(이미
  Auction 락 이후에 실행되던 위치라 순서 변경은 필요 없었다). uk_auto_bid_setting_active_slot
  UNIQUE 제약은 그대로 마지막 방어선으로 남겨뒀다 - 두 겹 방어(사전 locking 검사 + DB UNIQUE)다.
- PLACE_BID: BidCommandService.cancelOwnActiveAutoBidIfPresent()도 이 locking finder로
  바꿨다(이미 Auction 락 이후 실행되던 위치라 순서 변경은 필요 없었다).
```

**데드락 위험 재확인**: 세 경로 모두 이제 "Auction FOR UPDATE → own-setting FOR UPDATE →
competitor FOR UPDATE" 순서를 지킨다. own-setting은 호출자 본인의 단일 row이고 competitor
쿼리는 `user.id <> :userId`로 호출자 본인을 제외하므로 두 락이 같은 row를 다시 잡는 일은 없다.
Auction 락이 이미 모든 write 트랜잭션을 완전히 직렬화하므로(#45), 이 시점에 같은 auction의
AutoBidSetting row를 두고 경쟁할 수 있는 다른 살아있는 트랜잭션은 없다(#45와 동일 근거).

**회귀 전환**: 버그를 재현했던 `AutoBidCapUpdateStaleReadMySqlIT`를
`AutoBidCapUpdateConcurrencyMySqlIT`로 이름을 바꾸고 `@Tag("experiment")`를 제거했다 - 시나리오
(초기 cap=100, 동시 PATCH 200/150)는 그대로 유지하고 delay 대상만 새 locking finder로
맞췄다. 정책 assertion(`최종 cap >= 200000`)도 약화하지 않았다. 실행 순서에 따라 어느 요청이
200/409(40907)를 받는지는 갈릴 수 있어 HTTP 성공/실패 개수는 느슨하게(200 또는 409만 허용, 500
불허) 확인하고, 최종 DB invariant(cap >= 200000)만 고정 assert한다 - 실제 MySQL에서 green.

### 같은 메커니즘이 있던 나머지 두 경로

```text
CREATE_AUTO_BID의 40908 사전 검사
  - RESOLVED. findCurrentByAuctionIdAndUserIdForUpdate()로 교체해 사전 검사 자체도 stale하게
    "없음"을 반환하지 않는다. uk_auto_bid_setting_active_slot UNIQUE 제약은 그대로 최종
    방어선으로 남아있다 - 사전 검사가 이제 locking read라 대부분의 경우 40908이 사전 검사에서
    바로 나가고, DB UNIQUE는 여전히 이론적 마지막 backstop 역할을 한다.
    AutoBidConcurrencyMySqlIT(기존, "서로 다른 key 동시 CREATE → 정확히 1개만 성공") 재확인 완료.

PLACE_BID의 cancelOwnActiveAutoBidIfPresent
  - RESOLVED. findCurrentByAuctionIdAndUserIdForUpdate()로 교체했다.
  - 신규 `AutoBidCancelOnConcurrentManualBidMySqlIT`로 실제 MySQL 강제 재현/검증했다:
    ManualBidConcurrencyRaceIT(#35)와 동일한 AuctionRepository delay 패턴으로 CREATE_AUTO_BID가
    먼저 Auction 락을 잡고 보유하도록 강제한 뒤, 그 커밋 직후 Manual Bid가 own-setting을 locking
    read로 확인해 정확히 취소하는지(`autoBidCanceled=true`, 설정이 CANCELED로 전이) 검증한다 -
    green.
```

### 참조 조회 vs mutable 조회 구분

```text
UserRepository.findById() — 이 command들 안에서는 User를 mutate하지 않는 참조 조회다. 이번
  Proxy price-state locking invariant(AutoBidSetting 읽기-쓰기 보호)의 대상이 아니다. 다만
  "안전하다"고 단정하지 않는다 - 이 트랜잭션이 안 바꾸더라도 다른 트랜잭션이 penaltyUntil 같은
  입찰 판단 필드를 동시에 바꾸면 이 non-locking read도 stale decision을 낼 수 있다. User의
  mutable eligibility state(penalty 등)에 대한 read consistency는 이번 #46 follow-up의 범위가
  아니며 별도 정책 범위로 남겨둔다.

AutoBidSetting 조회(findCurrentByAuctionIdAndUserIdForUpdate, write 경로 전용) — "mutable,
  business-decision" 조회. 읽은 값(maxAmount/status)이 그대로 (a) 검증 조건에 쓰이고 (b) 같은
  트랜잭션에서 그 엔티티 자체가 mutate(changeMaxAmount/cancel)되어 flush된다.
  read-then-overwrite 패턴이라 non-locking이면 stale write가 가능했다 - Auction이
  findByIdForUpdate로 이미 이렇게 보호받는 것과 같은 이유로 locking current read로 바꿨다
  (위 RESOLUTION 참고). 순수 조회(findByAuctionIdAndUserIdAndActiveSlotTrue, GET
  /auto-bids/me 등)는 여전히 non-locking 그대로다 - business 결정에 쓰이지 않는다.
```

### A안 적용 후 검증 중 발견 — `ProxyMixedConcurrencyMySqlIT` 3-way 동시 CREATE의 "전원 201" assertion은 concurrency invariant가 아니었다

A안 적용 후 `ProxyMixedConcurrencyMySqlIT`의 3인 동시 LIVE AutoBid CREATE 테스트를 20회 반복
실행해 약 1/3(19회 중 7회) 확률로 실패를 관찰했다. 원인은 lock 경합/데드락이 아니라 **정상
도메인 규칙**이었다 - cap 구성(150000/300000/200000)에서 Auction 락이 세 요청을 직렬화하는
순서상 최약 cap(150000)이 다른 두 참가자(300000/200000)보다 나중에 처리되면, 그 둘의 pairwise
경쟁으로 `currentPrice`가 205000까지 오르고 `minCapAmount`가 210000이 되어 150000 cap이
정당하게 `409/40906 CAP_TOO_LOW`로 거절된다(응답 body로 직접 확인). 6가지 처리 순서 중 최약
cap이 마지막인 경우가 2가지라 이론상 1/3 확률과 실측 결과가 일치한다.

즉 이 테스트가 원래 하드코딩했던 "3명 모두 항상 201"은 **concurrency correctness invariant가
아니라 실행 순서에 의존하는 잘못된 assertion**이었다 - #46의 lock-ordering 수정이 만든 결함이
아니다(own-setting/competitor 락이 늘어나며 지연이 커져 "최약 cap이 마지막에 처리되는" 순서가
더 자주 나오게 되어 노출 빈도만 높아졌다). production Proxy 가격 정책은 변경하지 않았고, 테스트만
domain invariant에 맞게 고쳤다: 최강(B)/차강(C) cap은 항상 201을 요구하고, 최약(A) cap은
201 또는 409/40906만 허용하며, 응답 성공 개수 대신 트랜잭션 종료 후 DB post-state(ACTIVE 최대
1개, winner=최강 cap, 나머지 CAP_REACHED, 거절된 요청은 row 미생성, currentWinner-Bid 일치,
가격 단조증가, winner effectiveCap 초과 금지)를 assert하도록 수정했다 - 수정 후 8회 연속 green
확인.

참고: 20회 반복 중 20번째 실행이 미완료로 남은 것은 반복 루프 전체에 건 10분 커맨드 타임아웃
때문이었다 - 개별 테스트 자체의 hang/데드락 증거로 취급하지 않는다(개별 실행에서 hang이
재현되면 그때 별도 조사).

## #55 Implementation Notes

`feat/#55-auction-query-final`에서 #25의 Repository/pagination/stable ordering 기반은
유지한 채, Auction 조회 계약(#1/#3)을 완성하고 Similar(#18)/Likes(#19/#20)를 신규 구현했다.
#33~#36 concurrency 실험 결과 문서(protocol.md/environment.md/summary.md)는 raw CSV와
대조 검증만 하고 **내용을 바꾸지 않았다** — 이미 이전 작업에서 완전히 확정돼 있었다(아래
참고).

### GET /auctions/{id} — RESOLVED, 필드 2개만 gap

`AuctionDetailResponse`를 flat 표현에서 FINAL contract §1의 중첩 shape
(`product`/`seller`/`myState`)로 전면 재작성했다.

```text
product.productId/brand/grade/imageUrls    MATCH (Product 기존 필드 그대로)
product.name/subName                       근사치 - 아래 "남은 gap" 참고
seller.sellerId/nickname/profileImageUrl   MATCH (User 기존 필드 그대로)
seller.completedSalesCount                 DEFERRED DATA SOURCE GAP(0 고정, semantics 미충족) - 아래 "남은 gap" 참고
description/startPrice/currentPrice/bidIncrement/minNextBidAmount/minCapAmount  MATCH
startsAt/endsAt/serverTime                 MATCH (TimePolicy 재사용)
aiEstimatedPrice/aiPriceReason             MATCH - Product.recommendedPrice/reason
                                            (PricingResult 기반 실제 pricing 결과)를 그대로
                                            재사용한다. fake 값이 아니다.
aiRecommendedAutoBidCap                    MATCH - §4에서 이미 확정된 정책(buyer 전용 추천
                                            소스 없음 -> minCapAmount)을 재사용했다. 새
                                            정책을 만들지 않았다.
bidCount/isLiked/likeCount                 MATCH
myState 전체(isSeller/isHighestBidder/canBid/cannotBidReason/bidRestrictedUntil/
  autoBidStatus/autoBidCap)                MATCH - Auction.determineCannotBidReason()(#40)
                                            /AutoBidSettingRepository(#41) 등 기존 domain
                                            source를 그대로 재사용했다.
finalPrice                                 MATCH - status==ENDED && currentWinner!=null일 때만
                                            currentPrice, 아니면 null(유찰 포함).
```

**남은 gap(fake 값 대신 명시적으로 근사치로 남김)**:

- `product.name`/`product.subName`: `Product` 엔티티에 전용 컬럼이 없다(`brand`/`model`/
  `colorway`만 구조화 필드). `ProductDisplayName`(신규 유틸)이 `brand+model+colorway`를
  합성해 `name`으로, `model`을 `subName`으로 대체한다 - 실제 컬럼이 생기기 전까지의
  근사치이며 완료 보고에서 별도 gap으로 보고했다.
- `seller.completedSalesCount`: **DEFERRED DATA SOURCE GAP.** Order 도메인이 없어(§Not
  Implemented Yet #12/#13, #56에서 구현 예정) 항상 `0`을 반환하지만, 이 값은 "실제로 0건"을
  의미하지 않는다 - FINAL contract의 non-null(Int, O) 계약을 어기지 않기 위한 shape-only
  placeholder다. Order 도메인 없이 production schema/domain을 이번 #55에서 확장하지 않았고,
  Order가 #56에서 구현되면 그때 실제 카운트로 연결한다.
- **인증(RESOLVED)**: `GET /auctions/{id}`/`GET /auctions/{id}/bids`/`GET /auctions/{id}/similar`
  3개 endpoint 모두 비로그인 접근을 최종 정책으로 확정했다. 기존 계약 문서(§0.1)에는
  Authorization이 필수(O)로 잘못 표기되어 있었으나, 실제로는 상세조회 도입 시점부터
  비로그인 접근을 허용해온 의도된 도메인 결정이었다 - production 코드는 변경하지 않고
  `docs/auction-api-spec-final.md`의 해당 3개 endpoint Authorization 표기를 O -> X(optional)로
  수정해 문서를 실제 구현에 맞췄다. 인증 헤더가 있으면 개인화(myState/isLiked/isMine)를
  적용하고 없으면 중립값을 반환하는 동작은 그대로다. 이 항목은 §Auth: Bearer token
  (Mock auth vs Bearer token 구현 방식 자체의 gap)과는 별개이며, 그 gap은 여전히 유효하다.

쿼리는 기존 `findByIdWithProductAndWinner`(#40, `/live` 전용으로 이미 있던 product+seller+
currentWinner fetch join)를 그대로 재사용했다 - 새 쿼리를 만들지 않았다.

### GET /auctions/{id}/bids — RESOLVED

`BidResponse`에 `bidderMasked`(`NicknameMasker` 재사용)/`isMine`/`isHighest`/`bidType`을
추가했다. `isHighest`는 "목록 위치"나 "이 페이지의 최댓값"이 아니라 현재 `Auction.currentWinner`
+`currentPrice`와 정확히 일치하는 단 하나의 Bid만 true로 계산한다(지시대로). #25의 기존
`Pageable`/`latest`·`oldest`/`createdAt + id` stable ordering과 페이지네이션 테스트는
전혀 건드리지 않았다 - `BidRepository`의 두 쿼리 메서드는 정렬/페이징 절은 그대로 두고
`join fetch b.user`만 추가했다(아래 N+1 항목 참고).

### GET /auctions/{id}/similar — 신규 구현, same-brand heuristic

최초 구현 후 프론트 확인 결과를 반영해 최종 정책을 확정했다:

```text
대상: 상세 화면 "추천상품" 영역(별도 추천경매/추천상품 API 없음 - 이 endpoint가 그 역할을 겸함)
선정: 같은 brand + 자기 Auction/Product 제외 + 노출 가능(LIVE/SCHEDULED)
정렬: endAt asc, id asc (deterministic tie-break - endAt이 같아도 항상 같은 순서)
개수: 최대 4건
```

**AI/embedding 추천이 아니다.** `recommendation.ProductVectorService`(개인화 추천용, 목적이
다름)는 의도적으로 쓰지 않았다. 선정 로직은 `AuctionRepository.findSimilarByBrand()` 한
메서드에만 있고, `AuctionQueryService.getSimilarAuctions()`의 나머지(자기 제외 파라미터/
likeCount·isLiked 배치 조회/envelope 조립)는 선정 기준이 바뀌어도 재사용 가능하도록
의도적으로 결합을 최소화했다 - 향후 추천 품질을 바꿀 때 그 한 쿼리(또는 그 쿼리를 호출하는
한 줄)만 교체하면 된다.

### POST/DELETE /auctions/{id}/likes — 신규 구현

`AuctionLike` entity(`auction_likes` 테이블, `uk_auction_like_auction_user` UNIQUE(auction_id,
user_id))를 신규 추가했다. FINAL contract §19/§20이 "이미 좋아요한 상태에서 POST"/"좋아요
없는 상태에서 DELETE"에 대한 별도 에러를 정의하지 않아, 계약이 정의한 유일한 실패 표면(404)을
넘어서는 에러를 만들지 않는 가장 보수적인 해석으로 두 경우 모두 멱등 처리(현재 상태 그대로
반환)했다 - 임의 정책이 아니라 계약이 침묵하는 부분에 대한 최소 해석이다.

**동시 좋아요 race 처리 — 시행착오 기록**: 처음엔 "사전 exists-check + saveAndFlush() +
catch(DataIntegrityViolationException)"를 같은 트랜잭션에서 처리했는데, 실제 MySQL 동시
요청(`AuctionLikeConcurrencyMySqlIT`)에서 진 쪽이 500으로 샜다 - flush 실패 이후 같은
영속성 컨텍스트/트랜잭션을 계속 쓰는 게 안전하지 않았다. `REQUIRES_NEW`로 삽입만 격리하는
두 번째 시도는 `@DataJpaTest` 단위 테스트에서 실패했다 - REQUIRES_NEW 트랜잭션이 별도
커넥션이라, 같은 테스트의 (아직 커밋되지 않은) Auction/User fixture를 보지 못해 FK
violation을 진짜 "중복" race로 오판했다. 최종적으로 `IdempotencyClaimService.
claimAndExecute`/`resolveAfterConflict`(#32)와 동일한 claim/resolve-after-conflict 패턴을
그대로 적용했다: `AuctionLikeCommandService.like()`가 실패하면 예외를 잡지 않고 그대로
던져 그 트랜잭션만 깨끗하게 롤백시키고, `AuctionLikeService`(오케스트레이터, non-transactional)
가 `DataIntegrityViolationException`을 잡아 `currentLikeState()`(완전히 새 트랜잭션)로
현재 상태를 재조회한다 - InnoDB가 같은 unique key의 두 번째 INSERT를 첫 트랜잭션의 commit까지
블로킹한다는 성질(#41과 동일 근거)에 의존한다. 실제 MySQL(Testcontainers)로 동시 좋아요 시
row가 1개만 생성되는지 검증했다(`AuctionLikeConcurrencyMySqlIT`, 4회 연속 green).

### N+1 Audit — 실측으로 2건 발견 후 수정

Hibernate `Statistics`(`getPrepareStatementCount()`)로 실제 쿼리 수를 세는 회귀 테스트를
추가해(이 저장소 최초의 자동화된 N+1 회귀) 두 건을 실측으로 발견했다:

```text
1. Bid history: Bid.user가 LAZY라 bidderMasked 계산 시 페이지 크기만큼 SELECT가 반복됨
   -> BidRepository의 두 쿼리에 join fetch b.user 추가(countQuery는 join 없이 별도 유지),
      정렬/페이징 자체는 무변경.
2. Similar: Product.imageUrls(@ElementCollection, LAZY)가 후보 개수만큼 SELECT 반복됨
   -> Pageable과 collection fetch join을 함께 쓸 수 없어(firstResult/maxResults + fetch
      join 문제) 대신 Product.imageUrls에 @BatchSize(20) 추가 - 여러 Product의 imageUrls를
      IN 쿼리 하나로 묶어 로딩한다. 페이징 쿼리 구조 자체는 바꾸지 않았다.
```

Like count/isLiked(Similar), likeCount/isLiked(상세조회)는 처음부터 배치 조회
(`countByAuctionIdIn`/`findLikedAuctionIds`)로 설계해 N+1이 없었다.

### #33~#36 Concurrency Experiment 문서 — 검증만, 변경 없음

`docs/experiments/concurrency/{protocol,environment,summary}.md`를 raw CSV 4개
(`no-lock-correctness`/`pessimistic-correctness`/`no-lock-performance`/
`pessimistic-performance`)와 대조 검증했다 - **세 문서 모두 이미 완전히 확정돼 있었고
raw와 100% 일치해 내용을 수정하지 않았다.** 독립적으로 raw CSV를 재계산해(median/p95는
nearest-rank 방식, throughput은 문서에 정의된 공식) 다음을 재확인했다:

```text
No-lock correctness:      violations 3/20 (일치)
Pessimistic correctness:  violations 0/20 (일치)
No-lock performance:      overall median 27.33ms/p95 38.36ms, success median 29.25ms/p95
                           40.06ms(문서 40.07ms, 반올림 차이), attempt 253.77/s, successful 32.99/s
Pessimistic performance:  overall median 60.23ms/p95 111.75ms, success median 45.04ms/p95
                           99.42ms, attempt 82.56/s, successful 29.72/s
```

correctness workload(delay=1000ms)와 performance workload(delay=0)를 섞지 않는 점,
no-lock의 빠른 DB 예외 실패가 attempt throughput을 부풀릴 수 있다는 caveat, frozen
workload의 N/20을 production race probability로 표현하지 않는다는 caveat, single
instance/single MySQL 일반화 한계, 레이어별 대안 비교표(JVM lock/optimistic+retry/
pessimistic DB lock(채택)/Redis 분산 락/SERIALIZABLE/atomic update, 측정하지 않은
대안에는 수치를 채우지 않음)는 `summary.md`에 이미 전부 있었다(§Alternatives Considered,
§Limitations, §Interpretation Rules). raw CSV 4개는 읽기만 했고 수정/재생성하지 않았다.

## #56 Implementation Notes

`feat/#56-auction-result-backup-order`. #56-0에서 결과 화면(§10)/낙찰 포기(§11)/주문(§12-13)/
차순위 제안(§15-17) 관련 미확정 정책을 확정했고, #56-1에서 그중 GET /result와 낙찰자 최초
Order만 구현했다. Forfeit/BackupOffer accept/decline/pay/scheduler는 이번 범위가 아니다.

### #56-0 정책 확정 (구현 전 결정)

```text
1. Result 저장 방식
   - Result는 persisted entity가 아니다. Auction/Order(+ 향후 BackupOffer/UserPenalty) 상태로
     매 조회마다 계산한다. 별도 Award entity도 만들지 않는다.

2. Auction 종료 / 최초 Order 생성
   - GET /result는 side-effect free다 - Order를 lazy 생성하지 않는다.
   - AuctionSettlementService.settle(auctionId)가 명시적 command다. ENDED 대상, winner
     있으면 PAYMENT_PENDING Order 1건, purchasePrice=finalPrice, paymentDeadline=endsAt+24h.
     재실행해도 중복 생성하지 않는다.
   - 실제 LIVE->ENDED scheduler 호출부는 DEFERRED UNTIL LIFECYCLE INTEGRATION(#44/#45의
     ProxyTrigger.None과 동일 성격). payment expiry scheduler는 #57, BackupOffer expiry
     scheduler도 이번 범위가 아니다.

3. rank / myLastBidAmount
   - 사용자별 최고(=최신, monotonic이라 항상 같은 값) persisted Bid amount 내림차순.
   - 동일 금액은 그 금액에 먼저 도달한 Bid의 createdAt asc, id asc로 FIRST-IN WINS(§0.12
     그대로 재사용, 새 tie-break 규칙 없음).

4. Backup 후보
   - winner 이후 rank 2, rank 3까지만 후보다. rank 4 이하는 후보 아님.
   - backupEligible = rank가 2 또는 3이고 아직 소진되지 않았을 때 true. #56-1엔 BackupOffer가
     없어 "소진 여부"를 판정할 수 없으므로 LOST이고 rank가 2/3이면 항상 true다(#56-2에서
     BackupOffer 존재/상태로 정교화 예정).

5. Backup accepted Order의 PAYMENT_EXPIRED
   - rank 2 수락 후 Order가 PAYMENT_EXPIRED되면 rank 3으로 이양 가능 - 실제 만료 처리/penalty는
     #57. #56-1은 이 로직 자체가 없다(다음 후보 선정 로직을 아직 만들지 않았다 - #56-2에서
     BackupOffer와 함께 추가).

6. Penalty
   - #56-1 범위 아님(Penalty 엔티티 자체를 만들지 않았다). FORFEITED 1건 기록은 #56-2(forfeit
     구현 시점)로 미룬다. noShowCount/bidRestrictedUntil 정책은 §14대로 서버 설정이고 #57 범위다.

7. Lock / concurrency
   - 모든 post-auction write command는 Auction을 첫 authoritative lock으로 쓴다.
     AuctionSettlementService.settle()도 동일 원칙을 따른다(findByIdForUpdate가 이 트랜잭션의
     첫 statement) - Forfeit/Accept/Decline의 구체적 lock 순서는 #56-2 구현 시점에 다시 정리한다.

8. DB invariant
   - Order UNIQUE(auction_id, buyer_id) 추가(uk_order_auction_buyer). BackupOffer
     UNIQUE(auction_id, candidate_id)는 #56-2에서 BackupOffer 생성 시 추가한다. service
     check만으로 중복을 보장하지 않는다(#41 active-slot/#55 auction_like와 동일 방어 계층).

9. 40403 충돌
   - 40403은 BACKUP_OFFER_NOT_FOUND 전용으로 비운다. 기존 UserNotFoundException 호출부
     5개 파일 6곳(AuctionQueryService x2, BidCommandService, AutoBidCommandService,
     AuctionLikeCommandService, ProductRegistrationService)을 확인한 결과 전부 "이미
     MockAuthInterceptor가 인증 시점(401/40101)에 존재를 검증한 currentUserId"를 서비스
     내부에서 재조회하는 방어적 중복 체크였다 - 별도의 public "USER_NOT_FOUND" semantics가
     필요한 신규 요구는 없었다. #56-1 신규 코드(AuctionResultQueryService/
     AuctionSettlementService)는 이 패턴을 새로 추가하지 않았다(User 엔티티를 아예 조회하지
     않는다 - 아래 구현 노트 참고). 기존 6개 호출부 자체를 고치는 것(40403 해제)은 이번 이슈
     범위를 벗어나 손대지 않았다 - 여전히 후속 정리 대상으로 남는다(BackupOfferNotFoundException이
     실제로 40403을 쓰기 시작하는 #56-2 전에는 번호 충돌이 실제로 발생하지 않는다).

10. completedSalesCount
    - Order 도메인이 생겨 실제 PAID Order count로 연결한다(PAYMENT_PENDING/PAYMENT_EXPIRED/
      CANCELED 제외). N+1 없이 단일 count(*) 쿼리.

11. shippingFee (구현 중 추가로 확인한 사용자 결정)
    - Product/Auction 어디에도 배송비 필드가 없고 FINAL contract 모든 예시가 3000원 고정이라,
      전역 고정 상수(AuctionSettlementService.SHIPPING_FEE=3000L)로 처리하기로 확인했다.
      상품별 배송비가 필요해지면 그때 스키마를 바꾼다.
```

### #56-1 구현

```text
신규
  order/domain/Order.java              - createForWinner() 팩토리만 제공(차순위 수락자용
                                          팩토리는 #56-2에서 추가)
  order/domain/OrderStatus.java        - PAYMENT_PENDING/PAID/PAYMENT_EXPIRED/CANCELED 전부
                                          미리 선언(스키마 재변경 방지, PAID/EXPIRED/CANCELED로의
                                          실제 전이는 #56-1에 없음)
  order/repository/OrderRepository.java - findByAuctionIdAndBuyerId, completedSalesCount용 집계
  order/service/AuctionSettlementService.java - settle() 명시적 command
  auction/domain/AuctionResult.java    - NO_BIDS/WON/LOST/BACKUP_WAITING/FORFEITED/PAYMENT_EXPIRED
                                          전부 선언, 뒤 2개는 #56-1 경로에서 도달 불가(주석 참고)
  auction/dto/AuctionResultResponse.java
  auction/service/AuctionResultQueryService.java - getResult(), side-effect free

수정
  bid/repository/BidRepository.java    - findLatestBidPerUserOrderedByRank() 추가(사용자당
                                          최신 Bid만 남긴 뒤 §0.12 FIRST-IN WINS로 정렬)
  auction/AuctionController.java       - GET /auctions/{id}/result 매핑, AuctionResultQueryService
                                          의존성 추가
  auction/service/AuctionQueryService.java - seller.completedSalesCount를
                                          OrderRepository.countByAuction_Product_Seller_IdAndStatus로 연결
                                          (#55 DEFERRED DATA SOURCE GAP 해소)

테스트(신규)
  order/service/AuctionSettlementServiceTest.java       - @DataJpaTest, settle() 5케이스
  auction/service/AuctionResultQueryServiceTest.java    - @DataJpaTest, Result 6케이스
  auction/AuctionControllerTest.java (확장)              - GET /result 3케이스(WON/LOST/404)
  concurrency/AuctionSettlementMySqlIT.java              - 실제 MySQL, 동시 settle() 시
                                                            Order 1건만 생성되는지

수정(WebMvcTest 슬라이스 회귀 - AuctionController 생성자에 AuctionResultQueryService가
추가되며 기존 @WebMvcTest(AuctionController.class) 슬라이스 2곳이 컨텍스트 로딩에
실패해 함께 고쳤다)
  common/exception/GlobalExceptionHandlerTest.java     - @MockitoBean AuctionResultQueryService 추가
  common/auth/mock/MockAuthInterceptorTest.java        - @MockitoBean AuctionResultQueryService 추가
```

### 구현 중 확인된 assumption / 알려진 gap

```text
- rank/myLastBidAmount: 한 사용자의 Bid amount 시퀀스는 시간순으로 항상 증가한다(새 Bid는
  항상 직전 currentPrice보다 커야 저장됨, §0.13 monotonic) - 따라서 "최신 Bid" == "최고 Bid"라
  별도 MAX(amount) 집계 없이 findLatestBidPerUserOrderedByRank() 하나로 rank와
  myLastBidAmount를 동시에 구한다.

- settlement 전 ENDED 경매 조회: settle()이 아직 실행되지 않은 ENDED 경매를 실제 낙찰자가
  /result로 조회하면 Order가 없어 WON이 아니라 LOST로 보인다("GET은 side-effect free" 결정의
  직접적 결과, AuctionResultQueryServiceTest에 회귀로 고정해뒀다). 실제 production에서는
  lifecycle 스케줄러가 병합되면 /result 조회 시점엔 이미 settlement가 끝나 있는 것이 전제다 -
  #44/#45가 이미 남겨둔 DEFERRED UNTIL LIFECYCLE INTEGRATION 항목과 동일한 성격의 gap이다.

- /result의 Auction 상태 게이트 없음: ENDED가 아닌 경매(LIVE/SCHEDULED)에 대해 /result를
  호출해도 막지 않는다 - 입찰이 없으면 NO_BIDS, 있으면 rank/myLastBidAmount 기준으로 계산된다.
  계약이 이 경우를 명시하지 않고 프론트는 종료 후에만 이 화면을 쓰므로 별도 방어를 추가하지
  않았다.

- FORFEITED/CANCELED Order 분기: **RESOLVED (#56-2)**. determineResult()가 Penalty(FORFEITED)
  존재 여부를 authoritative signal로 판정한다(Order.status==CANCELED만으로 판정하지 않는다) -
  아래 `#56-2 구현` 참고.

- 40403 번호 충돌: **RESOLVED (#56-2)**. 아래 `#56-2 구현` 참고.
```

### #56-2 구현

Forfeit + BackupOffer 생성/조회. accept/decline은 여전히 다음 범위(#56-3)다.

```text
신규
  penalty/domain/Penalty.java             - forfeited() 팩토리만 제공(PAYMENT_EXPIRED 팩토리는 #57)
  penalty/domain/PenaltyType.java         - FORFEITED/PAYMENT_EXPIRED 전부 선언
  penalty/repository/PenaltyRepository.java
  backupoffer/domain/BackupOffer.java     - create() 팩토리 하나(WAITING만 생성) - purchasePrice만
                                             저장하고 totalAmount는 저장하지 않는다(조회 시점에
                                             ShippingPolicy로 계산 - accept가 아직 없어 얼려둘
                                             이유가 없음)
  backupoffer/domain/BackupOfferStatus.java - WAITING/ACCEPTED/DECLINED/EXPIRED 전부 선언
  backupoffer/repository/BackupOfferRepository.java
  backupoffer/service/BackupOfferQueryService.java - GET, side-effect free
  backupoffer/dto/BackupOfferResponse.java
  backupoffer/BackupOfferController.java  - GET /api/backup-offers/{id}만(accept/decline은 #56-3)
  order/service/AuctionForfeitService.java - forfeit() 명시적 command, lock 순서:
                                             Auction FOR UPDATE -> Order FOR UPDATE(신규 locking
                                             finder) -> validation -> Order.cancel() -> penalty ->
                                             BackupOffer -> commit(#56-0 확정)
  auction/dto/AuctionForfeitResponse.java
  common/exception/{NotAwardeeException,AlreadyPaidException,PaymentExpiredException,
    BackupOfferNotFoundException,InvalidOrderStatusException}.java
  common/util/ShippingPolicy.java         - Order/BackupOffer가 공유하는 shippingFee 상수(3000L)
                                             추출(#56-1의 AuctionSettlementService 전용 상수를
                                             승격) - AuctionSettlementService도 이걸 쓰도록 변경

수정
  order/domain/Order.java                 - cancel() 추가(PAYMENT_PENDING에서만 허용, 상태 전이
                                             가드를 domain boundary에 둠)
  order/repository/OrderRepository.java   - findByAuctionIdAndBuyerIdForUpdate(PESSIMISTIC_WRITE)
                                             추가 - forfeit 전용 locking current read
  auction/service/AuctionResultQueryService.java - BackupOffer/Penalty 의존성 추가, 판정 우선순위를
                                             #56-0이 정한 순서(NO_BIDS -> BACKUP_WAITING -> WON ->
                                             FORFEITED -> PAYMENT_EXPIRED -> LOST)로 전면 구현
  auction/AuctionController.java          - POST /auctions/{id}/award/forfeit 매핑 추가
  common/exception/GlobalExceptionHandler.java - 4개 신규 매핑(40303/40910/40914/40403) +
                                             UserNotFoundException을 40403/404에서 40101/401로
                                             재매핑(아래 "40403 번호 충돌 해소" 참고)

테스트(신규)
  order/domain/OrderTest.java                          - cancel() 상태 가드
  order/service/AuctionForfeitServiceTest.java          - @DataJpaTest, NOT_AWARDEE/ALREADY_PAID/
                                                          PAYMENT_EXPIRED/정상흐름/후보없음/재-forfeit
                                                          멱등 6케이스
  backupoffer/service/BackupOfferQueryServiceTest.java  - @DataJpaTest, purchasePrice/shippingFee/
                                                          totalAmount/deadline/404
  backupoffer/BackupOfferControllerTest.java            - 신규 WebMvcTest 슬라이스
  auction/AuctionControllerTest.java (확장)              - forfeit 3케이스(성공/40303/40914)
  auction/service/AuctionResultQueryServiceTest.java (확장) - FORFEITED, BACKUP_WAITING 2케이스
  concurrency/AuctionForfeitConcurrencyMySqlIT.java     - 실제 MySQL, 동시 재-forfeit 시 penalty/
                                                          BackupOffer가 각각 1건만 남는지
  concurrency/AuctionForfeitAtomicityMySqlIT.java       - 실제 MySQL, penalty INSERT 강제 실패 시
                                                          Order/BackupOffer까지 전부 롤백되는지
                                                          (#45 AuctionPriceAuditAtomicityMySqlIT와
                                                          동일한 CHECK 제약 강제 실패 기법 재사용)

수정(WebMvcTest 슬라이스 회귀 - AuctionController 생성자에 AuctionForfeitService가 추가되며
@WebMvcTest(AuctionController.class) 슬라이스 2곳이 컨텍스트 로딩에 실패해 함께 고쳤다)
  common/exception/GlobalExceptionHandlerTest.java     - @MockitoBean AuctionForfeitService 추가
  common/auth/mock/MockAuthInterceptorTest.java        - @MockitoBean AuctionForfeitService 추가
```

### 40403 번호 충돌 해소 (#56-2)

`UserNotFoundException`의 `GlobalExceptionHandler` 매핑을 `40403/404`에서 `40101/401`로 옮겼다
(단 한 곳, `handleUserNotFoundException()`). 재확인한 근거:

```text
- 기존 6개 호출부(AuctionQueryService x2/BidCommandService/AutoBidCommandService/
  AuctionLikeCommandService/ProductRegistrationService)를 전수 검색해 확인했다 - 전부
  "MockAuthInterceptor가 인증 시점(401/40101)에 이미 존재를 검증한 currentUserId"를 서비스
  내부에서 재조회하는 방어적 중복 체크였다. 별도의 public "USER_NOT_FOUND" semantics가 필요한
  신규 요구는 없었다 - #56-0 §9가 정한 대로 "인증/current user resolution 실패는 기존 40101
  흐름 사용"에 맞춘 재매핑이다.
- 기존 호출부 6곳 자체(예외를 던지는 지점)는 손대지 않았다 - 전부 이 한 handler로 수렴하므로
  production 코드는 그 외에 바꿀 곳이 없다(#46의 AuctionNotFoundException 40402->40401
  renumbering과 동일한 패턴).
- 테스트 blast radius: `UserNotFoundException`/`40403`을 참조하는 테스트를 전수 검색한 결과
  `ProductRegistrationServiceTest`(예외 타입만 검증, 숫자 코드 assertion 없음) 1건뿐이었다 -
  이 재매핑으로 깨지는 기존 테스트는 없었다(재확인만 했고 수정하지 않았다).
- `BackupOfferNotFoundException`이 이제 40403을 실제로 쓰기 시작해(`BackupOfferQueryService`),
  이 재매핑 없이는 두 예외가 같은 client-facing 번호를 공유해 프론트 분기 처리가 모호해졌을
  것이다 - #56-1에서 이미 예견하고 남겨뒀던 gap이 실제로 해소됐다.
```

### #56-3 남은 범위

```text
- POST /backup-offers/{id}/accept, POST /backup-offers/{id}/decline
  - accept: BackupOffer.status WAITING -> ACCEPTED, Order 생성(purchasePrice = 그 candidate의
    myLastBidAmount, paymentDeadline = 수락 시각 + 24h), Idempotency-Key 필수(§0.11,
    ACCEPT_BACKUP_OFFER:{backupOfferId})
  - decline: WAITING -> DECLINED, 다음 순위(rank 3)에게 새 BackupOffer 생성 가능 - #56-0 결정대로
    rank 3까지만(rank 4는 후보 아님)
  - next-backup-candidate 선정 로직: AuctionForfeitService.createBackupOfferIfCandidateExists()가
    rank 2 전용으로 하드코딩돼 있다 - rank 3 체이닝이 필요해지는 시점에 "다음 미소진 순위를 찾는"
    형태로 일반화해야 한다(지금은 재사용 가능한 형태로 분리돼 있지 않다 - #56-2 범위에서 Forfeit
    가 실제로 구현되며 처음 만들어진 로직이라, 재사용 지시가 있었지만 rank 2 하나만 다루는 이번
    형태로는 그대로 재사용할 수 없다는 점을 명시해 둔다)
- GET /orders/{id}, POST /orders/{id}/pay
- Backup accepted Order의 PAYMENT_EXPIRED -> rank 3 이양(#56-0 §5) - 실제 만료 처리 자체는 #57
- UserPenalty의 noShowCount/bidRestrictedUntil 반영 정책(§14, 서버 설정) - #57
- BackupOffer expiry / payment expiry scheduler - #57
- 실제 LIVE->ENDED settlement 호출부(scheduler) - DEFERRED UNTIL LIFECYCLE INTEGRATION(#44/#45와
  동일 성격, 여전히 미정)

## Freeze Blockers

```text
None
```

이전 감사(#36-B 1차)에서 발견됐던 3개 blocker는 다음과 같이 해소/재분류됐다:

- `TIE_BREAK_POLICY_UNRESOLVED` → **RESOLVED**(§Resolved Policies, 명세 §0.12).
- `ERROR_CODE_NUMBERING_CONFLICT` → blocker 아님으로 재분류. 스펙 자체는 명확(40401/40402/40403이 각각 무엇을 의미하는지 이미 확정)하고, 코드가 그 번호를 아직 안 따르는 것뿐이므로 `IMPLEMENTATION_GAP — DEFERRED`.
- `CONCURRENT_CONFLICT_NOT_MAPPED` → blocker 아님으로 재분류. 계약(`40909`, HTTP 409, 재시도 최대 1회)은 확정돼 있고 서버 구현만 없는 것이므로 `IMPLEMENTATION_GAP — DEFERRED`.

## Frontend Handoff

(변경 없음 — 스펙 부록 A를 그대로 반영, 화면 코드는 이번에도 수정하지 않음)

- 최고입찰자 화면: `canBid=false` + `cannotBidReason=ALREADY_HIGHEST_BIDDER`로 직접 입찰 버튼 비활성화.
- AutoBid `ACTIVE`/`CAP_REACHED` 사용자가 직접입찰 바텀시트를 열 때 "직접 입찰하면 현재 자동입찰이 중단됩니다." 경고 노출.
- 결제 화면 라벨을 "최종 낙찰가"가 아니라 `purchasePrice` 기반 "상품 금액"으로.
- PAYMENT_EXPIRED 화면의 `noShowCount`/`bidRestrictedUntil`은 `/api/me/penalties`에서(백엔드 선행 필요, §Not Implemented Yet #14).
- 차순위 제안 화면: `purchasePrice` = 실제 구매 가능 금액, `deadline` 라벨은 "구매 결정 기한"으로.
- 닉네임 마스킹 4개 고정 통일(서버 구현 전까지는 프론트 표시값과 실제 서버값이 다를 수 있음 — §Deferred Implementation Gaps).
- 연장 시 `/live.endsAt` 최신값 + `extensionCount`/`maxExtensions` 사용.
- "다른 상품 둘러보기" 버튼 목적지 미정 — 확정 필요.
- `FORFEITED` 결과 전용 화면 여부 결정 필요.
- PAID 상태(주문 상세) 레이아웃 정의 필요.

## Post-freeze Change Policy

**Internal implementation change**(별도 승인/프론트 동기화 없이 진행 가능):

```text
Pessimistic Lock, Proxy Bidding, AutoBid 내부 가격 결정 로직, scheduler,
transaction 구조, repository 조회 전략
```

**Contract change**(별도 issue + 프론트 동기화 필요):

```text
endpoint path/method 변경, required header 변경, request field 변경,
response field/타입/nullability 변경, HTTP status 변경, error code 변경,
enum 의미 변경, deadline 계산 규칙 변경, 입찰 금액 validation semantics 변경,
tie-break 정책 변경
```

§Deferred Implementation Gaps를 메꾸는 작업(정렬 검증 추가, 마스킹 적용, 응답 필드
추가, `40909` 매핑 추가)은 **이미 확정된 계약을 이제야 만족시키는 구현**이므로 contract
change가 아니다. 다만 §0-A 오류 코드 번호 재배정(예: `AuctionNotFoundException`을
40402→40401로 옮기는 것)은 기존 클라이언트 분기 처리를 깨뜨릴 수 있어 contract-change
취급을 권장한다.
