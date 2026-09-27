package com.vintic.backend.analyze.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ProductAnalysisSessionRepository extends JpaRepository<ProductAnalysisSession, Long> {

    // Vision 처리 소유권(claim/reclaim/complete/fail)의 authoritative read.
    // AuctionRepository.findByIdForUpdate와 동일한 원칙(#35): PESSIMISTIC_WRITE로 이 row를
    // 잠그되, Vision API 호출처럼 오래 걸리는 외부 호출 동안은 절대 이 락을 들고 있지 않는다 -
    // 호출부(VisionAttemptCoordinator/AnalysisFailureRecorder)가 짧은 트랜잭션 안에서만 쓴다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ProductAnalysisSession s where s.id = :id")
    Optional<ProductAnalysisSession> findByIdForUpdate(@Param("id") Long id);
}
