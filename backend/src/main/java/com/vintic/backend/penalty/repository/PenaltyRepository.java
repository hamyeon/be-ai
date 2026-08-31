package com.vintic.backend.penalty.repository;

import com.vintic.backend.penalty.domain.Penalty;
import com.vintic.backend.penalty.domain.PenaltyType;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PenaltyRepository extends JpaRepository<Penalty, Long> {

    // AuctionResultQueryService의 FORFEITED 판정 전용이다 - "이 auction에서 이 user에게
    // FORFEITED penalty가 기록돼 있는가"가 Result=FORFEITED의 authoritative signal이다
    // (Order.status==CANCELED만으로 판정하지 않는다 - forfeit 외의 CANCELED 원인이 생기더라도
    // 이 신호는 계속 정확하다).
    boolean existsByAuction_IdAndUser_IdAndType(Long auctionId, Long userId, PenaltyType type);
}
