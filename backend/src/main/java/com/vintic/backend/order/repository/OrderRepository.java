package com.vintic.backend.order.repository;

import com.vintic.backend.order.domain.Order;
import com.vintic.backend.order.domain.OrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    // GET /result의 WON/PAYMENT_EXPIRED 판정과 AuctionSettlementService의 중복 생성 방지
    // 사전조회가 공유하는 단일 조회다. uk_order_auction_buyer UNIQUE 덕분에 항상 0~1건이다.
    Optional<Order> findByAuctionIdAndBuyerId(Long auctionId, Long buyerId);

    // #56-1: seller.completedSalesCount(FINAL contract §1)를 실제 값으로 연결하기 위한 집계다.
    // "판매 완료"는 PAID Order만 센다 - PAYMENT_PENDING/PAYMENT_EXPIRED/CANCELED는 제외
    // (#56-0 확정 정책). Order.auction.product.seller 경로를 그대로 타는 단일 count(*) 쿼리라
    // N+1이 아니다.
    long countByAuction_Product_Seller_IdAndStatus(Long sellerId, OrderStatus status);
}
