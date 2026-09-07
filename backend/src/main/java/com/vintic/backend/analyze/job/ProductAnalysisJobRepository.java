package com.vintic.backend.analyze.job;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public interface ProductAnalysisJobRepository extends JpaRepository<ProductAnalysisJob, Long> {

    Optional<ProductAnalysisJob> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);

    // 조건부 UPDATE 메서드는 각각 독립된 트랜잭션에서 실행되어야 하므로(호출부가 하나의 큰
    // 트랜잭션으로 감싸지 않는다 - ProductAnalysisJobService 참고) 여기서 직접 @Transactional을
    // 선언한다. 상위 호출자가 트랜잭션이 없으면 REQUIRED 전파로 새 트랜잭션을 연다.
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ProductAnalysisJob j
            set j.status = com.vintic.backend.analyze.job.AnalysisJobStatus.QUEUED,
                j.updatedAt = CURRENT_TIMESTAMP
            where j.id = :id
              and j.status in (com.vintic.backend.analyze.job.AnalysisJobStatus.PENDING,
                                com.vintic.backend.analyze.job.AnalysisJobStatus.PUBLISH_FAILED)
            """)
    int markQueued(@Param("id") Long id);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ProductAnalysisJob j
            set j.status = com.vintic.backend.analyze.job.AnalysisJobStatus.PUBLISH_FAILED,
                j.updatedAt = CURRENT_TIMESTAMP
            where j.id = :id
              and j.status = com.vintic.backend.analyze.job.AnalysisJobStatus.PENDING
            """)
    int markPublishFailed(@Param("id") Long id);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ProductAnalysisJob j
            set j.status = com.vintic.backend.analyze.job.AnalysisJobStatus.PROCESSING,
                j.updatedAt = CURRENT_TIMESTAMP
            where j.id = :id
              and j.status in (com.vintic.backend.analyze.job.AnalysisJobStatus.PENDING,
                                com.vintic.backend.analyze.job.AnalysisJobStatus.QUEUED,
                                com.vintic.backend.analyze.job.AnalysisJobStatus.PUBLISH_FAILED)
            """)
    int markProcessing(@Param("id") Long id);
}
