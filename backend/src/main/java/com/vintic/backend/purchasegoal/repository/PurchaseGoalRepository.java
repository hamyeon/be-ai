package com.vintic.backend.purchasegoal.repository;

import com.vintic.backend.purchasegoal.domain.PurchaseGoal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PurchaseGoalRepository extends JpaRepository<PurchaseGoal, Long> {

    List<PurchaseGoal> findByUserIdOrderByCreatedAtDesc(Long userId);
}
