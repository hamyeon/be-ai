package com.vintic.backend.purchasegoal.repository;

import com.vintic.backend.purchasegoal.domain.PurchaseGoalMatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

// (goalId, auctionId) 이력 저장 구조만 제공한다.
public interface PurchaseGoalMatchRepository extends JpaRepository<PurchaseGoalMatch, Long> {

    // Day 4: Day 3 후보 목록(auctionIds) 중 이미 평가한 적 있는 것만 배치로 조회해 캐시로 쓴다 -
    // matched=false 이력도 그대로 재사용 대상이라 matched 조건을 걸지 않는다.
    List<PurchaseGoalMatch> findByGoalIdAndAuctionIdIn(Long goalId, List<Long> auctionIds);

    // Day 8: GET /api/purchase-goals/{id}/matches 전용 - 평가 이력 전체(matched=false 포함)를
    // 참여 이력과 별개로 노출한다. 최신 평가부터 보여준다.
    List<PurchaseGoalMatch> findByGoalIdOrderByEvaluatedAtDesc(Long goalId);
}
