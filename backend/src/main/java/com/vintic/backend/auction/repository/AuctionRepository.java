package com.vintic.backend.auction.repository;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.domain.AuctionStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AuctionRepository extends JpaRepository<Auction, Long> {

    // 입찰 read-modify-write의 최초 authoritative read. PESSIMISTIC_WRITE로 이 row에 대한
    // 다른 트랜잭션의 조회/수정을 이 트랜잭션이 commit/rollback할 때까지 블로킹한다(#35).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Auction a where a.id = :auctionId")
    Optional<Auction> findByIdForUpdate(@Param("auctionId") Long auctionId);

    // /live 전용 조회. 락 없이 product(판매자 비교용)와 currentWinner(마스킹/isMine용)만 한 번에 가져온다 -
    // polling마다 지연로딩으로 흩어진 쿼리를 내지 않기 위함이다. description/AI 필드/이미지 등은 건드리지 않는다.
    // #55: 상세조회(GET /auctions/{id})도 product/seller/currentWinner를 모두 필요로 해 그대로 재사용한다.
    @Query("""
            select a from Auction a
            join fetch a.product p
            join fetch p.seller
            left join fetch a.currentWinner
            where a.id = :auctionId
            """)
    Optional<Auction> findByIdWithProductAndWinner(@Param("auctionId") Long auctionId);

    // #55: 입찰 내역(GET /auctions/{id}/bids)의 isHighest 판정 전용 - currentWinner만 필요하고
    // product/seller는 필요 없어 findByIdWithProductAndWinner보다 가벼운 join만 쓴다.
    @Query("""
            select a from Auction a
            left join fetch a.currentWinner
            where a.id = :auctionId
            """)
    Optional<Auction> findByIdWithWinner(@Param("auctionId") Long auctionId);

    // #55: 상세 화면 "추천상품" 영역(GET /auctions/{id}/similar) 전용 - 프론트 확인 결과 이
    // 영역과 대응하는 별도 추천 API는 없고 이 endpoint 하나가 그 역할을 겸한다. 현재 선정 기준은
    // "같은 브랜드 + 자기 자신 제외 + 노출 가능한(LIVE/SCHEDULED) 경매"인 same-brand heuristic
    // 이다 - AI/embedding 추천이 아니며 recommendation 패키지의 ProductVectorService(개인화
    // 추천용, 이 endpoint와 목적이 다름)를 의도적으로 쓰지 않는다. 정렬은 endAt asc, id asc로
    // 고정해 endAt이 같은 경매끼리도 항상 같은 순서가 나오게 한다(deterministic tie-break).
    // 이 메서드 하나가 "선정 기준"의 유일한 진입점이다 - 나중에 실제 추천 로직으로 교체할 때
    // AuctionQueryService.getSimilarAuctions()의 나머지 부분(자기 제외/likeCount 배치 조회/
    // envelope 조립)은 건드리지 않고 이 쿼리(또는 이 메서드를 호출하는 한 줄)만 바꾸면 되도록
    // 의도적으로 결합을 최소화했다.
    @Query("""
            select a from Auction a
            join fetch a.product p
            where p.brand = :brand and a.id <> :excludeAuctionId and a.status in :statuses
            order by a.endAt asc, a.id asc
            """)
    List<Auction> findSimilarByBrand(
            @Param("brand") String brand,
            @Param("excludeAuctionId") Long excludeAuctionId,
            @Param("statuses") List<AuctionStatus> statuses,
            Pageable pageable
    );

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
