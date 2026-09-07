package com.vintic.backend.analyze.job;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;

import java.time.LocalDateTime;

// SQS 기반 비동기 분석 작업의 발행-대기-선점-완료 상태를 관리하는 orchestration record.
// 실제 Vision/Pricing 계산 결과는 이 엔티티가 갖지 않는다 (analyze.domain.ProductAnalysisSession과 별개 개념).
// 모든 상태 전이는 ProductAnalysisJobRepository의 조건부 UPDATE로만 이루어진다 - 엔티티에 상태 변경 메서드를 두지 않는다.
@Entity
@Table(
        name = "product_analysis_jobs",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_analysis_job_user_idempotency_key",
                columnNames = {"user_id", "idempotency_key"}
        )
)
@Getter
public class ProductAnalysisJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "object_key", nullable = false)
    private String objectKey;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AnalysisJobStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected ProductAnalysisJob() {
    }

    public static ProductAnalysisJob create(Long userId, String objectKey, String idempotencyKey) {
        ProductAnalysisJob job = new ProductAnalysisJob();
        job.userId = userId;
        job.objectKey = objectKey;
        job.idempotencyKey = idempotencyKey;
        job.status = AnalysisJobStatus.PENDING;
        LocalDateTime now = LocalDateTime.now();
        job.createdAt = now;
        job.updatedAt = now;
        return job;
    }
}
