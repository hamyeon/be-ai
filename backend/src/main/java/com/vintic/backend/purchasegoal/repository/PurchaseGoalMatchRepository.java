package com.vintic.backend.purchasegoal.repository;

import com.vintic.backend.purchasegoal.domain.PurchaseGoalMatch;
import org.springframework.data.jpa.repository.JpaRepository;

// (goalId, auctionId) 이력 저장 구조만 제공한다 - 조회/캐시 메서드는 그 로직을 실제로 쓰는
// Day 4에서 필요에 맞춰 추가한다.
public interface PurchaseGoalMatchRepository extends JpaRepository<PurchaseGoalMatch, Long> {
}
