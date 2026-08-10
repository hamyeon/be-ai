package com.vintic.backend.bid.repository;

import com.vintic.backend.bid.domain.Bid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BidRepository extends JpaRepository<Bid, Long> {

    long countByAuctionId(Long auctionId);

    Page<Bid> findByAuctionIdOrderByCreatedAtDescIdDesc(Long auctionId, Pageable pageable);

    Page<Bid> findByAuctionIdOrderByCreatedAtAscIdAsc(Long auctionId, Pageable pageable);
}
