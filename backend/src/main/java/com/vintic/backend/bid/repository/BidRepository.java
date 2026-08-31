package com.vintic.backend.bid.repository;

import com.vintic.backend.bid.domain.Bid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BidRepository extends JpaRepository<Bid, Long> {

    long countByAuctionId(Long auctionId);

    // #55: bidderMasked(NicknameMasker)가 각 Bid의 user.nickname을 필요로 한다 - user가
    // LAZY라 fetch join 없이 페이지를 순회하면 페이지 크기만큼 SELECT가 반복된다(N+1). join fetch는
    // to-one 연관관계(Bid.user)라 Pageable과 함께 써도 페이징 자체가 깨지지 않는다 - #25의 기존
    // 정렬(createdAt + id stable ordering)은 그대로 유지한다. countQuery는 join fetch 없이
    // 별도로 명시해 count 시 불필요한 조인을 하지 않는다.
    @Query(
            value = "select b from Bid b join fetch b.user where b.auction.id = :auctionId order by b.createdAt desc, b.id desc",
            countQuery = "select count(b) from Bid b where b.auction.id = :auctionId"
    )
    Page<Bid> findByAuctionIdOrderByCreatedAtDescIdDesc(@Param("auctionId") Long auctionId, Pageable pageable);

    @Query(
            value = "select b from Bid b join fetch b.user where b.auction.id = :auctionId order by b.createdAt asc, b.id asc",
            countQuery = "select count(b) from Bid b where b.auction.id = :auctionId"
    )
    Page<Bid> findByAuctionIdOrderByCreatedAtAscIdAsc(@Param("auctionId") Long auctionId, Pageable pageable);
}
