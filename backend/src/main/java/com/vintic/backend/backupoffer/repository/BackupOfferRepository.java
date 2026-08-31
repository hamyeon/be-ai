package com.vintic.backend.backupoffer.repository;

import com.vintic.backend.backupoffer.domain.BackupOffer;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
