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

    // Day4부터 PROCESSING/COMPLETED/FAILED 전이는 claimForProcessing/completeIfOwned/
    // failIfOwned만 쓴다(unfenced markProcessing/markCompleted/markFailed는 제거했다 - 프로덕션
    // 호출부가 없음을 rg로 확인). Day4 원자적 claim/stale 재선점. 신규 작업(PENDING/QUEUED/PUBLISH_FAILED)과 stale
    // PROCESSING(오래 전에 선점됐으나 완료/실패 보고가 없는 job) 재선점을 하나의 조건부 UPDATE로
    // 처리한다 - 조회 후 save가 아니라 이 UPDATE 자체가 동시 claim의 동시성 제어 지점이다.
    //
    // processing_started_at 기록과 stale 판정을 모두 DB의 UTC 시계(UTC_TIMESTAMP(6))로 통일한다.
    // processing_started_at을 기록하는 Worker와 그 값을 stale로 판정하는 Worker는 서로 다른 JVM일
    // 수 있어, 두 JVM의 시스템 시계/timezone이 다르면(예: 서로 다른 호스트, 서버 timezone 설정
    // 불일치) 애플리케이션이 계산한 절대시각을 주고받는 방식은 안전하지 않다 - 실제로 Testcontainers
    // MySQL(UTC 세션)과 JVM 기본 timezone(Asia/Seoul)이 다른 환경에서 애플리케이션 시계 기준
    // staleBefore를 그대로 비교했더니 방금 선점된 PROCESSING row가 9시간 "오래된" stale로 잘못
    // 판정되어 재선점되는 결함을 실제로 재현했다. 그래서 claimForProcessing은 절대시각을 주고받지
    // 않고 "몇 microsecond보다 오래됐는가"라는 상대 기간(staleAfterMicros)만 받고, 기록/비교를 모두
    // MySQL 서버가 자체 UTC 시계로 한 번에 수행한다 - 이러면 호출자 JVM의 시계나 timezone 설정과
    // 무관하게 정합성이 보장된다. HQL은 UTC_TIMESTAMP()/TIMESTAMPDIFF()를 이식성 있게 표현할 수
    // 없어 이 메서드만 native query를 쓴다(프로젝트가 MySQL 8.4로 고정돼 있어 허용됨).
    //
    // processing_started_at IS NULL도 stale로 취급한다: 이 컬럼이 없던 시절(Day4 이전)에 생성된
    // PROCESSING row가 남아있을 수 있기 때문이다. 이 취급은 "구버전 Worker가 이미 모두 종료된
    // 격리된 실험 환경"을 전제로 한다 - 구버전 Worker가 아직 살아서 그 job을 처리 중인 rolling
    // deployment 상황이라면 이 정책이 살아있는 작업을 즉시 재선점해버릴 수 있다. 이번 범위에는
    // 그런 배포 전략에 대한 별도 처리를 두지 않는다.
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE product_analysis_jobs
            SET status = 'PROCESSING',
                processing_started_at = UTC_TIMESTAMP(6),
                worker_id = :workerId,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = :id
              AND (
                   status IN ('PENDING', 'QUEUED', 'PUBLISH_FAILED')
                   OR (
                       status = 'PROCESSING'
                       AND (
                            processing_started_at IS NULL
                            OR TIMESTAMPDIFF(MICROSECOND, processing_started_at, UTC_TIMESTAMP(6)) > :staleAfterMicros
                       )
                   )
              )
            """, nativeQuery = true)
    int claimForProcessing(@Param("id") Long id, @Param("workerId") String workerId,
                            @Param("staleAfterMicros") long staleAfterMicros);

    // fenced 완료: 현재 lease 소유자(workerId 일치)만 COMPLETED로 전이할 수 있다. stale 재선점으로
    // lease를 잃은 옛 Worker가 뒤늦게 완료를 보고해도 이 조건에서 0건이 반영되어 막힌다.
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ProductAnalysisJob j
            set j.status = com.vintic.backend.analyze.job.AnalysisJobStatus.COMPLETED,
                j.updatedAt = CURRENT_TIMESTAMP
            where j.id = :id
              and j.status = com.vintic.backend.analyze.job.AnalysisJobStatus.PROCESSING
              and j.workerId = :workerId
            """)
    int completeIfOwned(@Param("id") Long id, @Param("workerId") String workerId);

    // fenced 실패: completeIfOwned와 동일한 fencing 규칙을 FAILED 전이에 적용한다.
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ProductAnalysisJob j
            set j.status = com.vintic.backend.analyze.job.AnalysisJobStatus.FAILED,
                j.updatedAt = CURRENT_TIMESTAMP
            where j.id = :id
              and j.status = com.vintic.backend.analyze.job.AnalysisJobStatus.PROCESSING
              and j.workerId = :workerId
            """)
    int failIfOwned(@Param("id") Long id, @Param("workerId") String workerId);
}
