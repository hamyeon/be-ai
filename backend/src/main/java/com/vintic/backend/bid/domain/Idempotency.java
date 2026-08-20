package com.vintic.backend.bid.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

// 성공한 요청의 중복 실행 방지만이 목적인 최소 범위 Idempotency 기록이다.
// 실패 응답 스냅샷, PENDING/FAILED 상태, 범용 operation registry는 의도적으로 갖지 않는다.
@Entity
@Table(
        name = "idempotencies",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_idempotency_identity",
                columnNames = {"user_id", "operation_scope", "idempotency_key"}
        )
)
public class Idempotency {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "operation_scope", nullable = false)
    private String operationScope;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "result_bid_id")
    private Long resultBidId;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected Idempotency() {
    }

    public static Idempotency claim(Long userId, String operationScope, String idempotencyKey, String requestHash) {
        if (userId == null) {
            throw new IllegalArgumentException("사용자는 필수입니다.");
        }
        if (operationScope == null || operationScope.isBlank()) {
            throw new IllegalArgumentException("operationScope는 필수입니다.");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey는 필수입니다.");
        }
        if (requestHash == null || requestHash.isBlank()) {
            throw new IllegalArgumentException("requestHash는 필수입니다.");
        }

        Idempotency idempotency = new Idempotency();
        idempotency.userId = userId;
        idempotency.operationScope = operationScope;
        idempotency.idempotencyKey = idempotencyKey;
        idempotency.requestHash = requestHash;
        idempotency.createdAt = LocalDateTime.now();
        return idempotency;
    }

    // 신규 입찰이 성공한 뒤에만 호출된다. claim 시점에는 아직 Bid가 없어 알 수 없다.
    public void attachResultBidId(Long bidId) {
        if (bidId == null) {
            throw new IllegalArgumentException("bidId는 필수입니다.");
        }
        this.resultBidId = bidId;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getOperationScope() {
        return operationScope;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public Long getResultBidId() {
        return resultBidId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
