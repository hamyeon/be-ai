package com.vintic.backend.order.repository;

import com.vintic.backend.order.domain.Order;
import com.vintic.backend.order.domain.OrderStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    // GET /result의 WON/PAYMENT_EXPIRED 판정과 AuctionSettlementService의 중복 생성 방지
    // 사전조회가 공유하는 단일 조회다. uk_order_auction_buyer UNIQUE 덕분에 항상 0~1건이다.
    // 순수 조회 전용이다 - forfeit처럼 이 row를 읽고 그대로 mutate하는 write 경로는 아래
    // findByAuctionIdAndBuyerIdForUpdate()를 써야 한다(#41/#46 follow-up과 동일한 이유).
    Optional<Order> findByAuctionIdAndBuyerId(Long auctionId, Long buyerId);

    // #56-2: AuctionForfeitService 전용 locking current read다. Auction FOR UPDATE 다음
    // statement로 호출되므로(lock ordering: Auction -> Order) 이 시점엔 Idempotency claim 같은
    // 사전 non-locking read가 없어 REPEATABLE READ snapshot이 아직 고정되지 않았다 - 그래도
    // 이 row 자체가 읽은 값(status)을 그대로 검증 조건과 이후 cancel() 덮어쓰기에 쓰는
    // read-then-overwrite 패턴이라, #46 follow-up이 확립한 규칙(mutable/business-decision
    // 조회는 locking current read)을 그대로 따른다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.auction.id = :auctionId and o.buyer.id = :buyerId")
    Optional<Order> findByAuctionIdAndBuyerIdForUpdate(
            @Param("auctionId") Long auctionId,
            @Param("buyerId") Long buyerId
    );

    // #56-1: seller.completedSalesCount(FINAL contract §1)를 실제 값으로 연결하기 위한 집계다.
    // "판매 완료"는 PAID Order만 센다 - PAYMENT_PENDING/PAYMENT_EXPIRED/CANCELED는 제외
    // (#56-0 확정 정책). Order.auction.product.seller 경로를 그대로 타는 단일 count(*) 쿼리라
    // N+1이 아니다.
    long countByAuction_Product_Seller_IdAndStatus(Long sellerId, OrderStatus status);
}
