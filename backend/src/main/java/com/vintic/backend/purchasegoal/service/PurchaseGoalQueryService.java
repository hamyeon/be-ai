package com.vintic.backend.purchasegoal.service;

import com.vintic.backend.common.exception.PurchaseGoalAccessDeniedException;
import com.vintic.backend.common.exception.PurchaseGoalNotFoundException;
import com.vintic.backend.purchasegoal.domain.PurchaseGoal;
import com.vintic.backend.purchasegoal.dto.PurchaseGoalResponse;
import com.vintic.backend.purchasegoal.repository.PurchaseGoalRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

// OrderQueryService.getOrder()와 동일한 관례를 쓴다 - 소유자 필터 없이 id로 먼저 조회해 404와
// 403(소유자 아님)을 구분한다.
@Service
public class PurchaseGoalQueryService {

    private final PurchaseGoalRepository purchaseGoalRepository;

    public PurchaseGoalQueryService(PurchaseGoalRepository purchaseGoalRepository) {
        this.purchaseGoalRepository = purchaseGoalRepository;
    }

    @Transactional(readOnly = true)
    public List<PurchaseGoalResponse> getMyGoals(Long userId) {
        return purchaseGoalRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .stream()
                .map(PurchaseGoalResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public PurchaseGoalResponse getGoal(Long goalId, Long userId) {
        PurchaseGoal goal = purchaseGoalRepository.findById(goalId)
                .orElseThrow(() -> new PurchaseGoalNotFoundException("존재하지 않는 구매 목표입니다. goalId: " + goalId));

        if (!goal.getUser().getId().equals(userId)) {
            throw new PurchaseGoalAccessDeniedException("접근 권한이 없는 구매 목표입니다. goalId: " + goalId);
        }

        return PurchaseGoalResponse.from(goal);
    }
}
