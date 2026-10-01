package com.vintic.backend.like.repository;

import com.vintic.backend.like.domain.AuctionLike;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AuctionLikeRepository extends JpaRepository<AuctionLike, Long> {

    boolean existsByAuctionIdAndUserId(Long auctionId, Long userId);

    Optional<AuctionLike> findByAuctionIdAndUserId(Long auctionId, Long userId);

    long countByAuctionId(Long auctionId);

    // #55 N+1 audit: Similar처럼 여러 auction의 likeCount가 필요한 목록 조회에서, item 개수만큼
    // count 쿼리를 반복하지 않기 위한 배치 조회다.
    @Query("""
            select l.auction.id as auctionId, count(l) as likeCount
            from AuctionLike l
            where l.auction.id in :auctionIds
            group by l.auction.id
            """)
    List<AuctionLikeCount> countByAuctionIdIn(@Param("auctionIds") List<Long> auctionIds);

    // 여러 auction 중 이 사용자가 좋아요한 것만 한 번에 골라낸다 - item당 exists 쿼리를
    // 반복하지 않기 위한 배치 조회다.
    @Query("""
            select l.auction.id
            from AuctionLike l
            where l.user.id = :userId and l.auction.id in :auctionIds
            """)
    List<Long> findLikedAuctionIds(@Param("userId") Long userId, @Param("auctionIds") List<Long> auctionIds);

    interface AuctionLikeCount {
        Long getAuctionId();

        long getLikeCount();
    }
}
