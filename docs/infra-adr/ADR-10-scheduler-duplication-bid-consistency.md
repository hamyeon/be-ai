# ADR-10. Scheduler 중복 실행과 경매 종료 vs 입찰 정합성

## Decision
다중 인스턴스에서 경매 종료의 정합성은 DB row lock + 락 이후 재검증으로 보장한다.
분산 락(ShedLock 등)은 도입하지 않는다. 종료 호출의 실제 결과를 반환값으로 드러내
Scheduler 로그가 실제 종료 건수를 정확히 보여주게 한다.

## Problem
@Scheduled는 애플리케이션이 뜬 모든 인스턴스에서 실행된다. API를 2대로 늘리면
두 인스턴스가 매분 0초에 같은 마감 경매를 동시에 종료하려 한다.
막지 못하면 중복 종료, 중복 낙찰 주문, 중복 정산이 발생할 수 있다.
또 종료와 마감 직전 입찰이 경쟁하면 마감 이후 입찰이 수락될 수 있다.

## 실측 (AWS, API 2대)
| | Day 11 | Day 12 |
|---|---|---|
| Scheduler 동시 실행 | 2회 (0.002초 차이) | 2회 (0.007초 차이) |
| 실제 상태 변경 (update auctions) | 3 = 경매 수 | 3 = 경매 수 |
| 낙찰 주문 | 경매당 1건 | 경매당 1건 |
| 마감 이후 수락 입찰 | 0 / 12,900 | 0 / 13,676 |
| Scheduler 로그 | 두 서버 모두 success=3 (과대 집계) | ended 3:0, notLive 0:3 (정확) |

## Alternatives
1. DB row lock + 재검증 (현재)
2. ShedLock 등 분산 락으로 한 인스턴스만 실행
3. 전용 Scheduler 인스턴스 / 리더 선출
4. 외부 트리거 (EventBridge Scheduler 등)

## Choice
1번 유지 + 종료 결과 반환(AuctionEndOutcome: ENDED/NOT_LIVE/NOT_DUE/NOT_FOUND).

## Reason
- 종료(endIfDue)와 입찰(placeManualBid)이 같은 Auction row를 FOR UPDATE로 잠그므로
  두 작업은 항상 직렬화된다. 락 이후 상태와 최신 endAt을 다시 읽어 판단한다.
- 입찰은 락 이후 서버 시각이 마감을 지났으면 LIVE여도 거절한다.
  Scheduler 폴링 간격(최대 1분) 동안의 "마감 지났지만 LIVE" 구간을 막는다.
- 정합성의 최종 방어선이 DB에 있으므로 인스턴스 수와 무관하다.
- 분산 락은 효율 최적화일 뿐, 락 만료·장애 시 중복 실행 가능성이 남아
  DB 방어선을 대체할 수 없다.

## 낙찰 결과 UNIQUE에 대한 결정
스프린트 문서 초안의 "낙찰 결과 auction_id UNIQUE"는 적용하지 않는다.
차순위 구매 수락(BackupOfferCommandService → Order.createForBackupAccept)이
같은 경매에 다른 구매자의 주문을 만들기 때문이다.
대신 기존 uk_order_auction_buyer (auction_id, buyer_id) UNIQUE와
경매 row lock 직렬화가 중복 낙찰을 막는다.

## Trade-off
- 모든 인스턴스가 같은 후보를 조회하고 락을 기다리는 중복 작업이 생긴다.
  인스턴스 수와 마감 경매 수가 늘면 락 대기와 DB 부하가 증가한다.
- 늦게 락을 얻은 인스턴스는 이미 종료된 경매를 확인만 하고 끝난다(notLive).

## Current Mitigation
- 회귀 테스트로 고정
    - AuctionEndSchedulerMultiInstanceMySqlIT: Scheduler 2개 동시 실행, 합계 ended = 경매 수
    - AuctionEndAtomicityMySqlIT: 동시 종료 결과 {ENDED, NOT_LIVE},
      입찰 vs 종료 동시 실행 시 종료 결과 항상 ENDED + 입찰 거절,
      정산 실패 롤백 후 다음 호출에서 정상 종료
- Scheduler 로그: ended / notLive / notDue / notFound / failed

## Residual Risk
- 외부 알림·결제·정산 후속 작업은 같은 DB 트랜잭션으로 묶을 수 없다.
- Scheduler 폴링 간격(1분)만큼 종료 반영이 지연된다(입찰은 서버 시각으로 막힘).
- AuctionStartScheduler 등 다른 Scheduler도 같은 과대 집계 구조를 가진다(미수정).
- AuctionEndAtomicityMySqlIT의 기존 테스트 일부가 테이블 전체 row 수에 의존해
  JUnit 실행 순서에 따라 깨질 수 있다(신규 테스트는 자기 데이터를 정리하는 방식으로 회피).

## Future
- 인스턴스 수가 늘어 중복 작업 비용이 커지면 ShedLock을 효율 최적화로 추가하되
  DB 락과 재검증은 유지한다.
- 종료 후속 이벤트는 AuctionClosed Outbox → Worker → Retry/DLQ로 확장한다(미구현).
- 다른 Scheduler에도 결과 반환 구조를 적용한다.
- 기존 IT의 전역 row 수 assert를 경매 단위 assert로 바꾼다.