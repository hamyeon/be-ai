package com.vintic.backend.backupoffer.repository;

import com.vintic.backend.backupoffer.domain.BackupOffer;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface BackupOfferRepository extends JpaRepository<BackupOffer, Long> {

    // AuctionForfeitService의 사전 존재 확인 전용이다(uk_backup_offer_auction_candidate가
    // 최종 방어선) - Auction/Order row lock이 이미 이 트랜잭션을 직렬화하므로 non-locking으로
    // 충분하다(같은 이유로 AuctionSettlementService.settle()의 Order 사전조회도 non-locking).
    Optional<BackupOffer> findByAuctionIdAndCandidateId(Long auctionId, Long candidateId);

    // GET /backup-offers/{id} 전용 - product 요약을 위해 auction.product까지 한 번에 가져온다.
    @Query("""
            select bo from BackupOffer bo
            join fetch bo.auction a
            join fetch a.product
            where bo.id = :backupOfferId
            """)
    Optional<BackupOffer> findByIdWithAuctionAndProduct(@Param("backupOfferId") Long backupOfferId);

    // #56 종료 전 lock order 수정: BackupOfferCommandService.lockOfferViaAuctionFirst()의 1단계
    // (offerId -> auctionId 식별용) 전용이다. 순수 스칼라 프로젝션이라 BackupOffer 엔티티 자체를
    // hydrate하지 않는다 - 여기서 얻는 auctionId 외의 어떤 값도 business decision에 쓰지 않는다.
    @Query("select bo.auction.id from BackupOffer bo where bo.id = :backupOfferId")
    Optional<Long> findAuctionIdById(@Param("backupOfferId") Long backupOfferId);

    // BackupOfferCommandService(accept/decline) 전용 locking current read다. 항상 Auction FOR
    // UPDATE를 먼저 획득한 뒤에만 호출된다(lockOfferViaAuctionFirst() 2단계 이후 3단계) - Forfeit/
    // Settlement와 동일한 lock ordering(Auction 먼저)을 여기서도 지킨다. status를 읽고 그대로
    // accept()/decline()에서 덮어쓰는 read-then-overwrite 패턴이라 non-locking이면 stale write가
    // 가능해(#46 follow-up과 동일한 이유) locking read를 쓴다.
    //
    // 의도적으로 auction/product를 join fetch하지 않는다 - MySQL의 SELECT ... FOR UPDATE는 join된
    // 모든 테이블의 해당 row에 락을 건다. 이미 Auction을 별도로 먼저 잠갔으므로 여기서 또 join으로
    // 잠그면 같은 트랜잭션 안에서 중복 락을 거는 것이고, 다른 트랜잭션과는 여전히 Auction 우선
    // 순서를 지키므로 있으나 마나다 - 불필요한 join만 없앤다. accept/decline은 Order 생성에
    // 필요한 Auction 참조(FK)만 있으면 되고 그건 지연 로딩 프록시로 충분하다 - Product 데이터
    // 자체가 필요 없다(응답에 product 요약이 없다, §16/§17).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select bo from BackupOffer bo where bo.id = :backupOfferId")
    Optional<BackupOffer> findByIdForUpdate(@Param("backupOfferId") Long backupOfferId);
}
