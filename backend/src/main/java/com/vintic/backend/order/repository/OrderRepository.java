package com.vintic.backend.order.repository;

import com.vintic.backend.order.domain.Order;
import com.vintic.backend.order.domain.OrderStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
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

    // #57-1: GET /orders/{orderId} 전용 - product 요약을 위해 auction.product까지 한 번에
    // 가져온다(BackupOfferRepository.findByIdWithAuctionAndProduct와 동일 패턴). side-effect
    // 없는 조회라 non-locking이다.
    @Query("""
            select o from Order o
            join fetch o.auction a
            join fetch a.product
            where o.id = :orderId
            """)
    Optional<Order> findByIdWithAuctionAndProduct(@Param("orderId") Long orderId);

    // #57-1: POST /orders/{orderId}/pay 전용 locking read다. Order 단일 row 전이라 Auction을
    // 잠글 필요가 없다(계약상 pay는 Auction.status를 바꾸지 않는다) - forfeit/BackupOffer accept가
    // 확립한 "Auction -> Order" lock ordering과 교차하지 않으므로 새 데드락 경로가 생기지 않는다.
    // status를 읽고 그대로 pay()에서 덮어쓰는 read-then-overwrite 패턴이라 non-locking이면 stale
    // write가 가능해(#46 follow-up과 동일한 이유) locking read를 쓴다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :orderId")
    Optional<Order> findByIdForUpdate(@Param("orderId") Long orderId);

    // #57-2: OrderExpirationScheduler가 이번 회차에 처리할 후보를 고르는 non-locking 조회다.
    // id만 스칼라로 뽑는다 - status/deadline 같은 business decision은 여기서 내리지 않고,
    // OrderExpirationService.expireIfDue()가 이 id로 다시 locking read를 해 authoritative하게
    // 재확인한다(식별 전용, BackupOfferRepository.findAuctionIdById와 동일한 원칙).
    @Query("select o.id from Order o where o.status = :status and o.paymentDeadline < :now")
    List<Long> findExpiredPendingOrderIds(@Param("status") OrderStatus status, @Param("now") LocalDateTime now);

    // #57-2: OrderExpirationService.expireIfDue()의 1단계(orderId -> auctionId 식별 전용) -
    // BackupOfferRepository.findAuctionIdById와 동일한 패턴이다. 여기서 얻는 auctionId 외의
    // 어떤 값도 business decision에 쓰지 않는다.
    @Query("select o.auction.id from Order o where o.id = :orderId")
    Optional<Long> findAuctionIdById(@Param("orderId") Long orderId);
}
