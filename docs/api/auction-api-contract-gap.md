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
| ACTIVE/CAP_REACHED modification | 상향만 허용, 아니면 `409 / 40907 CAP_NOT_INCREASED`. (명세 §7) |
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
| Direct bid alignment 검증 | Frozen (§9, `40913 BID_NOT_ALIGNED`) | 없음 — `Auction.placeManualBid()`는 `amount < currentPrice + bidIncrement` 최소금액만 체크, 배수 정렬은 미검증 | 후속 직접 입찰 보완 issue |
| Nickname masking (`/bids`) | Frozen (§0.9, 항상 4개 별표) | `NicknameMasker`(#40)는 구현됐으나 `GET /bids`에는 아직 적용 안 함 — 여전히 마스킹 없이 raw `bidderId` 노출, `isMine`/`isHighest`도 미구현 | 후속 issue(`/bids` final DTO 정리 시 `NicknameMasker` 재사용) |
| Numeric error mapping | Frozen (§0-A) | 불일치: `AuctionNotFoundException`이 40401이 아닌 **40402** 사용, 스펙의 40402/40403(ORDER_NOT_FOUND/BACKUP_OFFER_NOT_FOUND) 자리를 각각 `AuctionNotFoundException`/`UserNotFoundException`이 점유 중. `/live`, `/auto-bid/recommendation`(#40 신규)도 동일하게 40402를 그대로 사용해 기존 gap과 번호를 맞춤 | 후속 issue — Order/BackupOffer 구현 전에 정리 필요(그때 가서 번호 충돌 발생) |
| `40909 CONCURRENT_CONFLICT` mapping | Frozen (§0-A, HTTP 409 / code 40909 / 재시도 최대 1회) | 없음 — DB lock 예외(`CannotAcquireLockException`, #34 raw에서 135/160 관찰)가 catch-all `Exception` 핸들러로 떨어져 **500 / 50001**로 응답됨 | 후속 issue |
| Auth: Bearer token | Frozen (§0.1) | Mock 인증(`X-User-Id` 헤더 + `MockAuthInterceptor`) | 후속 issue(인증 시스템 도입 시) |
| Time 직렬화: ISO-8601 절대시각 (저장된 timestamp) | Frozen (§0.4) | DTO가 `LocalDateTime` 사용 — 실제 직렬화 결과가 `+09:00` 같은 절대 offset을 포함하지 않음. `/live.endsAt`(#40 신규, `Auction.endAt` 그대로 사용)도 동일 gap을 공유한다 — JVM timezone/DB 저장 timezone/배포 환경 timezone convention이 프로젝트 전체에서 확정되어 있지 않아(`application-local.yml`의 `serverTimezone=Asia/Seoul`은 로컬 프로필의 JDBC 연결 파라미터일 뿐이고 DATETIME↔LocalDateTime 매핑에는 적용되지 않으며, `application-dev.yml`엔 그 설정조차 없고, JVM `-Duser.timezone`/`TZ`도 프로젝트 어디에도 없음) 임의로 offset을 부여하지 않았다. `/live.serverTime`은 저장값이 아니라 응답 생성 시점에 새로 만드는 값이라 이 gap과 무관 — `Instant`로 구현해 계약을 충족한다(아래 `#40 Implementation Notes` 참고) | 후속 issue — 전역 time semantics 확정 후 저장된 auction timestamp 전체(startAt/endAt 등)를 absolute-time contract에 맞게 정리 |
| Endpoint #1 응답 shape (`myState`, `product`, `seller`, AI 필드, `isLiked`/`likeCount`, `finalPrice`, `serverTime`, `minNextBidAmount`/`minCapAmount`) | Frozen (§1) | `AuctionDetailResponse`가 flat한 내부 표현(`id, productId, sellerId, currentWinnerId, ...`)만 제공 | 후속 issue |
| Endpoint #9 응답 shape (`minNextBidAmount`, `highestBidderMasked`, `isHighestBidder`, `autoBidCanceled`, `proxyResponded`, `endsAt`, `extensionCount`) | Frozen (§9) | `PlaceBidResponse`에 없음(대신 비계약 필드 `auctionId`, raw `currentWinnerId`, `bidAt` 보유) | 후속 issue, alignment 구현과 함께 진행 권장 |
| `/live.extensionCount` / `/live.maxExtensions` | Frozen (§2, 둘 다 required) | **필드 자체를 응답에서 생략**(#40) — 종료 연장 정책(트리거 시점/연장 분/최대 횟수)이 도메인 어디에도 없고(`Auction` 엔티티, `application.yml` 등 전체 확인), #40 지시사항상 이 수치를 이번 이슈에서 새로 결정하지 말라고 명시돼 있어 구조적으로 값을 만들 수 없음. 임의 값(예: `extensionCount=0` 고정, `maxExtensions=3` 등)으로 계약을 가짜로 통과시키지 않았다 | 종료 연장 정책/구현 이슈에서 필드 추가 |
| `AutoBidSetting` 재등록 스키마 제약 | Frozen (§5, "CANCELED는 terminal, 재등록 시 새 row 생성") | `auto_bid_settings`에 `UNIQUE(auction_id, user_id)` 제약이 있어(#40에서 확인) 같은 (경매, 사용자) 조합으로 CANCELED row와 신규 row를 동시에 가질 수 없음 — 재등록 정책이 스키마 레벨에서 구조적으로 막혀 있음. #40은 `/live`가 이 제약(항상 0~1건) 덕분에 "최신 row 조회" 로직 없이 단순 조회로 충분하다는 점만 활용했고 제약 자체는 변경하지 않음 | AutoBid 등록 API 구현 이슈에서 unique 제약 재설계 필요(예: 복합 unique에 상태/버전 포함, 또는 CANCELED row soft-delete) |

## Not Implemented Yet

계약은 확정돼 있지만 endpoint 자체가 아직 없다. 이 상태는 freeze를 막지 않는다.

| # | Endpoint | 비고 |
| --- | --- | --- |
| 5 | POST /auctions/{id}/auto-bids | `AutoBidSetting` 도메인 엔티티 + `AutoBidSettingStatus` enum(RESERVED/ACTIVE/CAP_REACHED/CANCELED, 명세와 일치)만 존재, service/controller 없음 |
| 6 | GET /auctions/{id}/auto-bids/me | — |
| 7 | PATCH /auctions/{id}/auto-bids/me | — |
| 8 | DELETE /auctions/{id}/auto-bids/me | — |
| 10 | GET /auctions/{id}/result | — |
| 11 | POST /auctions/{id}/award/forfeit | — |
| 12 | GET /orders/{id} | Order 패키지 자체가 없음 |
| 13 | POST /orders/{id}/pay | — |
| 14 | GET /me/penalties | `User.isBidRestricted()` 내부 로직만 존재, 조회 API 없음 |
| 15 | GET /backup-offers/{id} | BackupOffer 패키지 없음 |
| 16 | POST /backup-offers/{id}/accept | — |
| 17 | POST /backup-offers/{id}/decline | — |
| 18 | GET /auctions/{id}/similar | 다른 경로의 별개 추천 API(`/api/recommendations/auctions`, `/api/curations`)만 존재, 이 계약과 무관 |
| 19 | POST /auctions/{id}/likes | — |
| 20 | DELETE /auctions/{id}/likes | — |

## Endpoint Status Summary

```text
endpoint implemented (엔드포인트 존재 여부 기준, contract 완전 일치를 의미하지 않음): 5/20 (#1, #2, #3, #4, #9)
not implemented yet: 15/20
implementation gaps (contract resolved, code lagging): #1, #2, #3, #9 — 위 §Deferred Implementation Gaps 참고
  (#4는 이번 #40에서 확정한 fallback-only 정책을 그대로 구현해 gap 없음 — 아래 §40 Implementation Notes 참고)
contract conflicts: 0/20
```

`5/20`은 endpoint가 존재하는지만 세는 카운트다 — 그 endpoint가 계약 전 필드를 충족한다는
뜻이 아니다. 특히 #2 `/live`는 field-level 상태가 아래처럼 갈려 endpoint 자체 상태를
**`IMPLEMENTED WITH DEFERRED GAPS`**로 기록한다(`IMPLEMENTATION_GAP`과 동일 범주,
"완전 MATCH"가 아님을 명확히 하기 위한 표기).

구현된 endpoint들이 `CONTRACT_CONFLICT`가 아니라 `IMPLEMENTATION_GAP`인 이유는 각각의
차이가 "계약이 두 가지로 갈려서"가 아니라 "계약은 하나로 정해져 있는데 코드가 아직 그
계약을 안 지켜서(또는 #2처럼 일부 필드가 구조적으로 아직 불가능해서)" 발생하기 때문이다
(§7 기준 재정리).

## #40 Implementation Notes

`GET /auctions/{id}/live`(#2)와 `GET /auctions/{id}/auto-bid/recommendation`(#4)을 이번
#40에서 구현했다.

```text
#2 /live       — IMPLEMENTED WITH DEFERRED GAPS (endsAt, extensionCount, maxExtensions 미충족)
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
  endsAt (Auction.endAt을 LocalDateTime 그대로, offset 없음)          IMPLEMENTATION_GAP — DEFERRED
  extensionCount / maxExtensions                                    IMPLEMENTATION_GAP — DEFERRED (필드 자체 생략)
```

`/auto-bid/recommendation`(#4)은 gap 없이 계약과 일치한다 — `aiRecommendedCap`이 항상
`minCapAmount`와 같은 것은 미구현이 아니라 §4에 명시된 fallback 정책 자체가 그렇다
(buyer 전용 AI 추천 소스가 도메인에 없음, `Product`의 판매가 추천값은 seller-side라 재사용
대상이 아님).

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
