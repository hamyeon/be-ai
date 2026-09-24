# Purchase Agent Day 0 계약 조사

조사 기준 커밋: `17c9346` (branch: `feat/be-purchase-agent`)
범위: 코드 읽기 전용 조사. 구현 없음.

---

## 1. 경매 종료 상태 변경과 Order 생성이 같은 트랜잭션인지 / 승패 판정 근거

**결론**: 같은 물리 트랜잭션이다. `AuctionEndService.endIfDue()`가 `@Transactional`이고, 그 안에서
호출하는 `AuctionSettlementService.settle()`도 `@Transactional`(기본 REQUIRED)이라 하나의
트랜잭션으로 합류한다. `end()`가 실패하면 `settle()`(Order 생성 포함)까지 함께 롤백된다.

Agent가 승패를 판정할 근거는 **`Order` 존재 여부**다. `settle()`은 `auction.getCurrentWinner()`가
null이 아니면 `Order.createForWinner(...)`를 생성하고, `NotificationRecorder`로
`AUCTION_WON` 알림을 기록한다. 낙찰자가 없으면(`NO_BIDS`) Order를 만들지 않고
`Optional.empty()`를 반환한다. 즉 "정산 완료"는 `Auction.status == ENDED` +
(Order 존재 또는 winner == null 확인)으로 판단할 수 있다.

**근거 파일:줄**
- `backend/src/main/java/com/vintic/backend/auction/service/AuctionEndService.java:53-73`
- `backend/src/main/java/com/vintic/backend/order/service/AuctionSettlementService.java:47-81`

**구현 시 영향**
- Agent가 결과를 폴링할 때는 `Order.findByAuctionIdAndBuyerId(auctionId, agentUserId)` 존재 여부로
  승패를 판정하면 된다(정산 재실행에도 안전 - `existing.isPresent()`면 그대로 반환).
- `end()`~`settle()` 사이에 Agent 쪽 로직을 끼워 넣으려면 같은 트랜잭션에 편입시킬지,
  별도 이벤트/폴링으로 분리할지 결정이 필요하다(현재 코드에는 Agent 훅이 없음 — 미확정).

---

## 2. LIVE/SCHEDULED AutoBid 등록 진입점, 금액 검증, Goal 상태 변경과의 트랜잭션 결합 가능 여부

**결론**
- 진입점은 단일 메서드다: `AutoBidCommandService.createAutoBid(auctionId, userId, maxAmount[, idempotencyId])`.
  LIVE/SCHEDULED를 구분하는 별도 API는 없다 — 메서드 내부에서 `auction.getStatus()`로 분기한다.
  - `SCHEDULED`(코드상 auction 상태는 `RESERVED` 아님, `AuctionStatus.SCHEDULED`): `AutoBidSetting.reserve()`만 저장, `ProxyPriceEngine` 미호출.
  - `LIVE`: 저장 전에 `ProxyPriceEngine.resolve()`로 즉시 경쟁 가격을 계산해 반영.
- 금액 검증 규칙(등록 시): `maxAmount >= auction.getMinNextBidAmount()` 위반 시 `CapTooLowException`(40906).
  그 외 순서: 사용자 존재 → penalty(40903 계열) → 경매 종료/취소(`AuctionClosedException`) →
  마감시각 경과 → 판매자 본인 여부(`SellerCannotBidException`) → 기존 등록 중복(40908) → 금액.
  (update 시엔 ACTIVE/CAP_REACHED 상태면 `newMaxAmount > 기존값` 강제 — 40907.)
- **Goal 상태 변경과 한 트랜잭션으로 묶을 수 있는가**: 코드 구조상 가능하다. `createAutoBid`는
  순수 Spring `@Transactional`(REQUIRED)이고, 실제 호출 경로(`AutoBidService.createAutoBid` →
  `IdempotencyClaimService.claimAndExecute`)도 REQUIRED 트랜잭션 하나 안에서 idempotency claim
  insert + `command.apply(...)`(=createAutoBid 본체)를 실행한다. 외부 I/O(HTTP 등)가 이 경로에
  없으므로, Goal 상태 변경을 같은 서비스 메서드 호출 스택 안에 넣으면 자동으로 같은 트랜잭션에 묶인다.
  단, **`Goal` 영속 엔티티 자체가 현재 코드베이스에 존재하지 않는다** (`MatchGoal`은 DTO일 뿐이고,
  `ai/purchase` 패키지 주석에 "PurchaseGoal 엔티티는 백엔드 담당이 만든다(미구현)"라고 명시됨).

**근거 파일:줄**
- `backend/src/main/java/com/vintic/backend/autobid/service/AutoBidCommandService.java:82-186`(createAutoBid), `:192-295`(updateAutoBid)
- `backend/src/main/java/com/vintic/backend/autobid/service/AutoBidService.java:1-45`
- `backend/src/main/java/com/vintic/backend/bid/service/IdempotencyClaimService.java:49-74`
- `backend/src/main/java/com/vintic/backend/ai/purchase/match/MatchGoal.java:1-19` (엔티티 미구현 주석)

**구현 시 영향**
- Goal 엔티티/상태 설계가 Day 1 전제조건. 트랜잭션 결합 자체는 기술적으로 막히지 않는다.
- AutoBid 등록 락 순서는 "Auction FOR UPDATE → AutoBidSetting FOR UPDATE"로 고정돼 있음(주석 명시,
  `#45` 확정) — Goal 관련 락을 추가한다면 이 순서와 충돌하지 않게 배치해야 함.

---

## 3. AutoBidSetting이 취소·종료 후에도 남는지 / 참여 이력용 별도 테이블 필요 여부

**결론**
- **취소**: 남는다. `cancel()`은 row를 삭제하지 않고 `status=CANCELED, activeSlot=null`로 갱신한다.
  `activeSlot`이 CANCELED에서 null이 되는 이유는 UNIQUE 제약(`uk_auto_bid_setting_active_slot`,
  컬럼 `auction_id, user_id, active_slot`)이 "현재 유효 설정은 1건"만 강제하고, MySQL이 NULL을
  서로 다른 값으로 취급해 CANCELED 이력은 여러 건 누적 가능하게 설계돼 있다.
- **경매 종료**: `AuctionEndService`/`AuctionSettlementService`는 `AutoBidSetting`을 전혀
  건드리지 않는다(grep 결과 상태 전이 호출부는 `ProxyResolutionApplier`, `BidCommandService`,
  `AutoBidCommandService.cancelAutoBid` 뿐). 즉 경매가 ENDED가 돼도 패자의 `AutoBidSetting`은
  `ACTIVE`/`CAP_REACHED`/`RESERVED` 상태로 그대로 남고, "패배"를 나타내는 별도 상태(enum 값)는
  없다. `AutoBidSettingStatus`는 `RESERVED, ACTIVE, CAP_REACHED, CANCELED` 4종뿐.

**근거 파일:줄**
- `backend/src/main/java/com/vintic/backend/autobid/domain/AutoBidSetting.java:136-147`(cancel), `:26-30`(activeSlot 설계 주석)
- `backend/src/main/java/com/vintic/backend/autobid/domain/AutoBidSettingStatus.java:1-6`
- grep: 상태 전이 호출부 전수 조사 (`ProxyResolutionApplier.java:18-28`, `BidCommandService.java:231`, `AutoBidCommandService.java:304`) — 경매 종료 경로 없음

**구현 시 영향**
- row 자체는 삭제되지 않으므로 "몇 번 자동입찰에 참여했는지" 카운트는 `auto_bid_settings`를
  `userId`로 직접 집계하면 되고, 별도 이력 테이블이 필수는 아니다.
- 다만 "이겼는지/졌는지"는 이 테이블만으로 판단 불가 — 승리 여부는 `Order`(§1 참고) 또는
  `auction.currentWinner`와 대조해야 한다. Agent가 "낙찰 실패"를 구분해서 기록하려면 별도 필드나
  조인 쿼리가 필요함(**미확정** — 정책 결정 필요).

---

## 4. 경매 생성 후 매칭용 상품 정보 수정 가능 여부 / Matcher 결과 (goalId, auctionId) 재사용

**결론**
- `Product` 엔티티는 모든 필드가 `private`이고 **setter가 전혀 없다** — 생성자 1회 주입 후 불변.
- `ProductController`에는 `POST /calculate-price`, `POST /api/products`(생성), `GET /api/products`
  (목록)만 있고, **수정(PUT/PATCH) 엔드포인트가 존재하지 않는다**. 즉 현재 코드베이스 기준으로
  상품 등록 후 brand/model/colorway/sizeKr/conditionGrade/componentStatus를 바꿀 방법이 없다.
- `AuctionListing`(Matcher 입력 DTO)과 `new AuctionListing(...)` 생성 호출부를 `backend/src/main`
  전체에서 찾았으나 **호출 지점이 아직 없다** — 아직 어떤 서비스도 Auction/Product로부터
  `AuctionListing`을 만들어 Matcher를 실제로 호출하고 있지 않다(인터페이스만 존재).
- `ListingMatcher` 인터페이스 주석에 "결과는 (goal, auction) 단위로 저장해 재호출하지 않는다
  (백엔드 담당)"이라고 명시돼 있음 — 이는 AI 팀이 백엔드에 위임한 요구사항이지, 이미 구현된
  캐시/저장 로직이 아니다.

**근거 파일:줄**
- `backend/src/main/java/com/vintic/backend/product/domain/Product.java:36-52`(필드), `:92-154`(getter만 존재, setter 없음)
- `backend/src/main/java/com/vintic/backend/product/ProductController.java:1-55`(엔드포인트 전체)
- `backend/src/main/java/com/vintic/backend/ai/purchase/match/ListingMatcher.java:1-14`(저장 위임 주석)
- `backend/src/main/java/com/vintic/backend/ai/purchase/match/AuctionListing.java:1-18`

**구현 시 영향**
- 상품 정보는 현재 불변이므로 Matcher 결과를 `(goalId, auctionId)` 키로 캐시해도 "매칭 이후 상품이
  바뀌어 결과가 stale해지는" 위험은 코드상 없음(수정 API 자체가 없으므로).
- 단, Matcher 결과 저장 테이블/엔티티는 **미구현** — Day 1에서 새로 설계해야 함.

---

## 5. 알림 중복 방지 / DB 스키마 변경 방식 / AI 연결 시그니처

### 알림 중복 방지
**결론**: `Notification` 테이블의 `business_event_key`(`"{TYPE}:{resourceId}"`, 예:
`AUCTION_WON:55`) 컬럼에 DB UNIQUE 제약(`uk_notification_business_event_key`)을 걸어
최종 방어선으로 쓴다. 애플리케이션 레벨에서는 별도 pre-check 없이 소스 엔티티(Order 등)가
이미 "한 번만 생성/전이"를 보장하는 지점에서만 `NotificationRecorder.record()`를 호출한다.

**근거 파일:줄**
- `backend/src/main/java/com/vintic/backend/notification/domain/Notification.java:24-38`
- `backend/src/main/java/com/vintic/backend/notification/service/NotificationRecorder.java:38-45`

### DB 스키마 변경 방식
**결론**: Flyway/Liquibase 등 마이그레이션 도구가 레포에 없다(`backend/src/main/resources`에
migration 디렉터리 없음). `application-dev.yml`, `application-local.yml` 모두
`spring.jpa.hibernate.ddl-auto: update`로 설정돼 있어, JPA 엔티티(`@Entity`/`@Table`/`@Column`)
변경이 곧 스키마 변경이다. `application-prod.yml`의 ddl-auto 값은 이번 조사에서 열지 않음(**미확정**).

**근거 파일:줄**
- `backend/src/main/resources/application-dev.yml:11`
- `backend/src/main/resources/application-local.yml:11`

### AI 연결 시그니처 (내부 로직 미확인, 연결부만)

- `GoalDraft`(record, `ai/purchase/dto/GoalDraft.java:18-28`):
  `(modelQuery, brand, modelKey, minCondition: GoalCondition, hardMaxAmount: Long, sizeKr: Integer, freeTextConditions, confidence: double, warnings: List<String>)`.
  계약 문서 원 필드(`modelQuery/minCondition/hardMaxAmount/freeTextConditions/confidence`) 외
  `brand/modelKey/sizeKr/warnings`가 추가돼 있음.
- `GoalCondition`(enum, `ai/purchase/dto/GoalCondition.java:12-40`): `DS > S > A > B > C`,
  `satisfiedBy(GoalCondition listing): boolean`, `fromLabel(String): Optional<GoalCondition>`.
- `ListingMatcher`(interface, `ai/purchase/match/ListingMatcher.java:11-14`):
  `evaluate(MatchGoal goal, AuctionListing listing): MatchResult`.
  - `MatchGoal(modelKey, modelQuery, brand, freeTextConditions)` — minCondition/hardMaxAmount/sizeKr 제외(백엔드 pre-filter 담당).
  - `MatchResult(matched: boolean, semanticScore: double, reason: String, listingModelKey: String)`.
- `ModelAliases`(class, `ai/purchase/model/ModelAliases.java`): 주요 public 메서드 —
  `find(String text): Optional<Match>`, `byKey(String modelKey): Optional<ModelInfo>`,
  `isKnownKey(String modelKey): boolean`, `catalog(): List<ModelInfo>`, `aliasesOf(String modelKey): List<String>`,
  `static normalize(String value): String`.
- `PriceEstimateProvider`(interface, `ai/purchase/price/PriceEstimateProvider.java:15-18`):
  `estimate(PriceEstimateQuery query): Optional<PriceEstimate>`.
  - `PriceEstimateQuery(brand, model, colorway, sizeKr, conditionGrade, componentStatus)` +
    정적 팩토리 `of(Product product)` (`Auction.getProduct()`로 바로 생성 가능하다고 주석에 명시).
  - `PriceEstimate(estimatedPrice: int, lowerBound: int, upperBound: int, source: Source[USED_MARKET|KREAM], sampleCount: int, reason: String, computedAt: LocalDateTime)`.
  - 주석: "ENGAGE 직전에 서버가 다시 계산한다. `Product.recommendedPrice`를 읽지 않는다" — 판매자가
    부풀릴 수 있는 클라이언트 값이라 신뢰하지 않는다는 명시적 정책.

---

## 정책 초안(`CAP_RATIO=1.0`, SCHEDULED 24h, 순위 할인율→종료시각→semanticScore→auctionId) 과 AutoBid 금액 규칙 충돌 여부

**결론**: 코드 레벨에서 직접 충돌하는 지점은 발견되지 않았다. 단, AutoBid 등록/수정 시 다음
기존 규칙은 CAP_RATIO 계산 결과에 상관없이 항상 적용되므로 Agent가 계산한 금액이 이 규칙을
만족하지 못하면 등록 자체가 실패한다(이건 "충돌"이 아니라 지켜야 할 제약):
- 등록/수정 시 `amount >= auction.getMinNextBidAmount()` 미달이면 `CapTooLowException`
  (`AutoBidCommandService.java:118-123`, `:234-239`).
- 수정 시 기존 상태가 `ACTIVE`/`CAP_REACHED`면 신규 금액이 기존 `maxAmount`보다 커야 함
  (`CapNotIncreasedException`, `AutoBidCommandService.java:240-246`).
- `EffectiveCapCalculator`는 `maxAmount`를 `bidIncrement` 그리드에 맞춰 내림 처리할 뿐(§0.13),
  CAP_RATIO 정책과 독립적인 계산이라 충돌 없음(`EffectiveCapCalculator.java:14-26`).

---

## Day 1을 막는 문제

1. **`Goal`(구매 목표) 영속 엔티티가 코드베이스에 전혀 없다.** `MatchGoal`은 Matcher 입력용 DTO일
   뿐이고, 주석에도 "PurchaseGoal 엔티티는 백엔드 담당이 만든다(미구현)"이라고 명시돼 있다.
   상태 필드, 상태 전이 규칙 자체가 미정 — §2/§3 질문에 대한 답이 이 엔티티 설계에 종속된다.
2. **Matcher 결과 저장(캐시) 테이블이 없다.** `ListingMatcher` 계약이 "(goal, auction) 단위로
   저장해 재호출하지 않는다"를 백엔드에 요구하지만 대응 엔티티/repository가 없다.
3. **`AuctionListing`을 실제로 만들어 Matcher를 호출하는 오케스트레이션 코드가 없다.** 스캔
   서비스(SCHEDULED 24h 탐색, 랭킹 정렬 등)가 아직 존재하지 않아 §5 정책 초안을 어느 클래스에
   구현할지부터 설계가 필요하다.
4. **패배(낙찰 실패) 상태를 나타낼 방법이 없다.** `AutoBidSettingStatus`에 "LOST" 류 상태가 없고,
   경매 종료 시 `AutoBidSetting`을 건드리는 코드가 없어, Agent가 "이 경매는 졌다"를 판단하려면
   `Order`/`auction.currentWinner`와의 조인이 필요하다 — Day 1에서 설계 확정 필요.
5. `application-prod.yml`의 `ddl-auto` 값 및 실제 배포 스키마 변경 절차는 미확인(**미확정**) —
   운영 반영 전 별도 확인 필요.
