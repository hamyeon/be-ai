package com.vintic.backend.auction.repository;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.domain.AuctionStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface AuctionRepository extends JpaRepository<Auction, Long> {

    // 추천 후보. 아직 끝나지 않은 경매만 대상이다 - 이미 끝난 경매를 추천해봐야 참여할 수 없다.
    // 상품까지 함께 읽는다. 추천은 상품 벡터로 정렬하므로 매건 product를 다시 조회하면 N+1이 된다.
    @Query("""
            select a from Auction a
            join fetch a.product
            where a.status in :statuses
            """)
    List<Auction> findOpenAuctions(@Param("statuses") List<AuctionStatus> statuses);

    // Cold Start Fallback - 마감 임박순. 지금 참여할 수 있는 것부터 보여준다.
    @Query("""
            select a from Auction a
            join fetch a.product
            where a.status = :status and a.endAt > :now
            order by a.endAt asc
            """)
    List<Auction> findEndingSoon(
            @Param("status") AuctionStatus status,
            @Param("now") LocalDateTime now,
            Pageable pageable
    );

    // Cold Start Fallback - 인기순. 입찰이 많이 붙은 경매를 검증된 상품으로 본다.
    // 입찰이 하나도 없는 경매도 후보에 들어와야 하므로 left join을 쓴다.
    @Query("""
            select a from Auction a
            join fetch a.product
            left join Bid b on b.auction = a
            where a.status in :statuses
            group by a
            order by count(b) desc, a.endAt asc
            """)
    List<Auction> findPopular(@Param("statuses") List<AuctionStatus> statuses, Pageable pageable);
}
