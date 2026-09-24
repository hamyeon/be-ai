package com.vintic.backend.purchasegoal.repository;

import com.vintic.backend.purchasegoal.domain.PurchaseGoal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface PurchaseGoalRepository extends JpaRepository<PurchaseGoal, Long> {

    List<PurchaseGoal> findByUserIdOrderByCreatedAtDesc(Long userId);

    // 아래 세 조건부 전이 메서드는 모두 clearAutomatically = true를 쓴다 - bulk UPDATE는 영속성
    // 컨텍스트(1차 캐시)를 거치지 않고 DB를 직접 바꾸므로, 이 goal이 이미 이전에 조회돼 관리 중이면
    // (예: cancelGoal의 소유자 확인 조회) 캐시를 정리하지 않을 경우 이후 findById()가 갱신 전 값을
    // 그대로 반환한다. 호출마다 영속성 컨텍스트 전체를 비우므로, 호출 전에 이후에도 계속 쓸 다른
    // 관리 엔티티를 들고 있으면 안 된다(Day 5 호출부들은 그렇게 쓰지 않는다).

    // Day 5: Agent 참여의 유일한 상태 전이 진입점. UPDATE ... WHERE 자체가 InnoDB에서 그 row의
    // 배타적 락을 잡은 채 실행되므로, 같은 goal에 대한 동시 UPDATE(다른 engage 시도, cancelGoal의
    // ACTIVE->CANCELLED 시도)는 이 트랜잭션이 commit/rollback할 때까지 자연히 직렬화된다 - 별도
    // SELECT ... FOR UPDATE가 필요 없다. 반환값(영향받은 row 수)이 1이어야만 실제로 이 트랜잭션이
    // 전이를 "이긴" 것이다 - 0이면 이미 다른 트랜잭션이 먼저 상태를 바꿨다는 뜻이므로 호출자는
    // AutoBid를 만들지 않고 그대로 멈춰야 한다.
    @Modifying(clearAutomatically = true)
    @Query("""
            update PurchaseGoal g
            set g.status = com.vintic.backend.purchasegoal.domain.PurchaseGoalStatus.ENGAGED,
                g.currentAuctionId = :auctionId,
                g.updatedAt = :now
            where g.id = :goalId
              and g.status = com.vintic.backend.purchasegoal.domain.PurchaseGoalStatus.ACTIVE
              and g.deadline > :now
            """)
    int transitionToEngaged(@Param("goalId") Long goalId, @Param("auctionId") Long auctionId, @Param("now") LocalDateTime now);

    // Day 5: cancelGoal의 1차 시도(ACTIVE -> CANCELLED). engage의 transitionToEngaged와 같은
    // goal row를 대상으로 하는 조건부 UPDATE라 - 두 트랜잭션 중 먼저 commit하는 쪽이 이기고,
    // 나중 트랜잭션은 이 메서드든 transitionToEngaged든 0을 돌려받는다.
    @Modifying(clearAutomatically = true)
    @Query("""
            update PurchaseGoal g
            set g.status = com.vintic.backend.purchasegoal.domain.PurchaseGoalStatus.CANCELLED,
                g.updatedAt = :now
            where g.id = :goalId
              and g.status = com.vintic.backend.purchasegoal.domain.PurchaseGoalStatus.ACTIVE
            """)
    int cancelFromActive(@Param("goalId") Long goalId, @Param("now") LocalDateTime now);

    // Day 5: cancelGoal의 2차 시도(ENGAGED -> CANCEL_REQUESTED). 1차 시도가 0을 반환했을 때만
    // 호출한다 - engage가 먼저 이겨 이미 ENGAGED가 된 경우, 취소 의사를 CANCEL_REQUESTED로
    // 기록한다. currentAuctionId/기존 AutoBid는 건드리지 않는다(Day 6이 정리 책임).
    @Modifying(clearAutomatically = true)
    @Query("""
            update PurchaseGoal g
            set g.status = com.vintic.backend.purchasegoal.domain.PurchaseGoalStatus.CANCEL_REQUESTED,
                g.updatedAt = :now
            where g.id = :goalId
              and g.status = com.vintic.backend.purchasegoal.domain.PurchaseGoalStatus.ENGAGED
            """)
    int requestCancelFromEngaged(@Param("goalId") Long goalId, @Param("now") LocalDateTime now);

    // Day 6 phase 1(만료): ENGAGED/CANCEL_REQUESTED는 대상이 아니다 - 참여 중인 경매는 deadline이
    // 지나도 결과가 나올 때까지 기다린다(phase 2가 그 정리를 한다). 후보를 개별 조회할 이유가
    // 없어(ACTIVE 상태에는 currentAuctionId가 없으므로 goal별 조건 분기가 필요 없다) Day 5의
    // 다른 전이들과 달리 단일 bulk UPDATE로 처리한다.
    @Modifying(clearAutomatically = true)
    @Query("""
            update PurchaseGoal g
            set g.status = com.vintic.backend.purchasegoal.domain.PurchaseGoalStatus.EXPIRED,
                g.updatedAt = :now
            where g.status = com.vintic.backend.purchasegoal.domain.PurchaseGoalStatus.ACTIVE
              and g.deadline <= :now
            """)
    int expireDueActiveGoals(@Param("now") LocalDateTime now);

    // Day 6 phase 2 후보 식별 전용(non-locking, id만). 실제 판단(경매가 아직 진행 중인지, 낙찰인지)은
    // PurchaseGoalResultObservationService.observeIfDue()가 매 goal마다 다시 authoritative하게 읽는다.
    @Query("""
            select g.id from PurchaseGoal g
            where g.status in (
                com.vintic.backend.purchasegoal.domain.PurchaseGoalStatus.ENGAGED,
                com.vintic.backend.purchasegoal.domain.PurchaseGoalStatus.CANCEL_REQUESTED
            )
            """)
    List<Long> findEngagedOrCancelRequestedGoalIds();

    // Day 6 phase 3 후보 식별 전용(non-locking, id만) - PurchaseGoalCandidateFinder.findCandidates()가
    // 호출 시점에 ACTIVE/deadline을 다시 확인하므로 여기서 걸러진 뒤 상태가 또 바뀌어도 안전하다.
    @Query("""
            select g.id from PurchaseGoal g
            where g.status = com.vintic.backend.purchasegoal.domain.PurchaseGoalStatus.ACTIVE
              and g.deadline > :now
            """)
    List<Long> findActiveGoalIdsForScan(@Param("now") LocalDateTime now);

    // Day 6 phase 2 결과 반영 4종. 전부 goalId + currentAuctionId(관찰한 그 경매) + 이전 상태를
    // WHERE에 함께 건다 - id/상태만 걸면, 패배로 ACTIVE 복귀 후 phase 3에서 곧바로 "다른" 경매에
    // 재참여(ENGAGED, currentAuctionId가 바뀜)한 goal을 다른 서버/느린 스캔이 뒤늦게 "그때 그
    // 경매" 결과로 잘못 덮어쓸 수 있다(중복/지연 관찰 방지).
    //
    // 낙찰: ENGAGED/CANCEL_REQUESTED 둘 다 목표를 그대로 이뤘으므로 결과가 같다(FULFILLED) -
    // 하나의 UPDATE로 합친다. currentAuctionId는 지우지 않는다(낙찰 경매를 가리키는 이력으로 남긴다).
    @Modifying(clearAutomatically = true)
    @Query("""
            update PurchaseGoal g
            set g.status = com.vintic.backend.purchasegoal.domain.PurchaseGoalStatus.FULFILLED,
                g.updatedAt = :now
            where g.id = :goalId
              and g.currentAuctionId = :auctionId
              and g.status in (
                  com.vintic.backend.purchasegoal.domain.PurchaseGoalStatus.ENGAGED,
                  com.vintic.backend.purchasegoal.domain.PurchaseGoalStatus.CANCEL_REQUESTED
              )
            """)
    int resolveWin(@Param("goalId") Long goalId, @Param("auctionId") Long auctionId, @Param("now") LocalDateTime now);

    // 패배(또는 경매 취소) + deadline이 아직 안 지남: 다음 경매를 찾을 수 있도록 ACTIVE로 복귀하고
    // currentAuctionId를 비운다.
    @Modifying(clearAutomatically = true)
    @Query("""
            update PurchaseGoal g
            set g.status = com.vintic.backend.purchasegoal.domain.PurchaseGoalStatus.ACTIVE,
                g.currentAuctionId = null,
                g.updatedAt = :now
            where g.id = :goalId
              and g.currentAuctionId = :auctionId
              and g.status = com.vintic.backend.purchasegoal.domain.PurchaseGoalStatus.ENGAGED
              and g.deadline > :now
            """)
    int resolveLossToActive(@Param("goalId") Long goalId, @Param("auctionId") Long auctionId, @Param("now") LocalDateTime now);

    // 패배(또는 경매 취소) + deadline이 이미 지남: 더 참여할 기회가 없으므로 EXPIRED로 끝낸다.
    @Modifying(clearAutomatically = true)
    @Query("""
            update PurchaseGoal g
            set g.status = com.vintic.backend.purchasegoal.domain.PurchaseGoalStatus.EXPIRED,
                g.updatedAt = :now
            where g.id = :goalId
              and g.currentAuctionId = :auctionId
              and g.status = com.vintic.backend.purchasegoal.domain.PurchaseGoalStatus.ENGAGED
              and g.deadline <= :now
            """)
    int resolveLossToExpired(@Param("goalId") Long goalId, @Param("auctionId") Long auctionId, @Param("now") LocalDateTime now);

    // CANCEL_REQUESTED 상태에서 패배(또는 경매 취소)하면 사용자가 이미 취소 의사를 밝힌 것이므로
    // deadline과 무관하게 CANCELLED로 끝낸다.
    @Modifying(clearAutomatically = true)
    @Query("""
            update PurchaseGoal g
            set g.status = com.vintic.backend.purchasegoal.domain.PurchaseGoalStatus.CANCELLED,
                g.updatedAt = :now
            where g.id = :goalId
              and g.currentAuctionId = :auctionId
              and g.status = com.vintic.backend.purchasegoal.domain.PurchaseGoalStatus.CANCEL_REQUESTED
            """)
    int resolveCancelRequestedToCancelled(@Param("goalId") Long goalId, @Param("auctionId") Long auctionId, @Param("now") LocalDateTime now);
}
