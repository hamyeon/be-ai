package com.vintic.backend.auction.audit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuctionPriceAuditRepository extends JpaRepository<AuctionPriceAudit, Long> {

    List<AuctionPriceAudit> findByAuctionIdOrderByCreatedAtAsc(Long auctionId);

    long countByAuctionId(Long auctionId);
}
