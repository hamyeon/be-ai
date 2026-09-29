package com.vintic.backend.purchasegoal.service;

import com.vintic.backend.purchasegoal.repository.PurchaseGoalRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

// Day 6 스캔 phase 1(만료). ACTIVE이고 deadline이 지난 Goal만 EXPIRED로 끝낸다 - ENGAGED/
// CANCEL_REQUESTED는 여기서 건드리지 않는다(참여 중인 경매는 deadline이 지나도 결과가 나올
// 때까지 기다린다, phase 2의 책임). PurchaseGoalRepository.expireDueActiveGoals()가 단일 bulk
// UPDATE라 goal 개수와 무관하게 한 번의 쿼리로 끝난다.
@Service
public class PurchaseGoalExpirationService {

    private final PurchaseGoalRepository purchaseGoalRepository;
    private final Clock clock;

    public PurchaseGoalExpirationService(PurchaseGoalRepository purchaseGoalRepository, Clock clock) {
        this.purchaseGoalRepository = purchaseGoalRepository;
        this.clock = clock;
    }

    @Transactional
    public int expireDueGoals() {
        LocalDateTime now = LocalDateTime.now(clock);
        return purchaseGoalRepository.expireDueActiveGoals(now);
    }
}
