package com.vintic.backend.penalty.repository;

import com.vintic.backend.penalty.domain.Penalty;
import com.vintic.backend.penalty.domain.PenaltyType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PenaltyRepository extends JpaRepository<Penalty, Long> {

    // AuctionResultQueryService의 FORFEITED 판정 전용이다 - "이 auction에서 이 user에게
    // FORFEITED penalty가 기록돼 있는가"가 Result=FORFEITED의 authoritative signal이다
    // (Order.status==CANCELED만으로 판정하지 않는다 - forfeit 외의 CANCELED 원인이 생기더라도
    // 이 신호는 계속 정확하다).
    // #57-2: OrderExpirationService의 PAYMENT_EXPIRED 중복 방지 사전 확인도 이 메서드를 그대로
    // 재사용한다(type=PAYMENT_EXPIRED로 호출) - Order row lock이 이미 이 트랜잭션을 (auction,
    // buyer) 단위로 직렬화하므로 non-locking으로 충분하다. uk_penalty_auction_user_type이
    // 최종 방어선이다.
    boolean existsByAuction_IdAndUser_IdAndType(Long auctionId, Long userId, PenaltyType type);

    // #57-2: GET /me/penalties(§14) 전용 - 최신순으로 이력을 반환한다.
    List<Penalty> findByUser_IdOrderByCreatedAtDesc(Long userId);
}
