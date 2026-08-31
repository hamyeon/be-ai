package com.vintic.backend.bid.repository;

import com.vintic.backend.bid.domain.Bid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

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

    // #56-1: GET /result의 rank/myLastBidAmount 계산 전용이다. 한 사용자가 같은 경매에 여러 번
    // 입찰해도 순위표에는 사용자당 한 행만 있어야 한다 - "사용자의 최신 Bid"만 남긴다(하위쿼리로
    // 사용자별 max(id) 행만 선별). Auction.placeManualBid()/ProxyPriceEngine 둘 다 새 Bid는
    // 항상 직전 currentPrice보다 커야만 저장하므로(§0.13 currentPrice monotonic), 한 사용자의
    // Bid amount 시퀀스는 시간 순으로 항상 증가한다 - 즉 "최신 Bid" == "그 사용자의 최고 Bid"라
    // myLastBidAmount와 rank 산정용 최고 금액이 서로 다른 값일 수 없다(별도 MAX(amount) 집계가
    // 필요 없다).
    // 정렬은 §0.12 FIRST-IN WINS를 그대로 적용한다 - amount desc, 동률이면 그 금액에 먼저
    // 도달한 Bid의 createdAt asc, id asc. 새 tie-break 규칙을 만들지 않았다.
    @Query("""
            select b from Bid b
            where b.auction.id = :auctionId
            and b.id in (
                select max(b2.id) from Bid b2 where b2.auction.id = :auctionId group by b2.user.id
            )
            order by b.amount desc, b.createdAt asc, b.id asc
            """)
    List<Bid> findLatestBidPerUserOrderedByRank(@Param("auctionId") Long auctionId);
}
