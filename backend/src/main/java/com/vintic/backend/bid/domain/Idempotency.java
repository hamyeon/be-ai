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

    // 모든 커맨드(PLACE_BID 포함)의 최초 성공 응답을 JSON으로 얼려 저장한다. 응답이 등록/수정
    // 시점의 Auction 상태(Proxy resolution 결과 등)에 의존해 나중에 재조회하면 값이 달라질 수
    // 있는 커맨드가 늘어나면서, PLACE_BID 전용 resultBidId 기반 replay(#32)는 더 이상 충분하지
    // 않아 제거했다 - 이 컬럼 하나로 통일한다.
    @Column(name = "response_snapshot", columnDefinition = "TEXT")
    private String responseSnapshot;

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

    // 커맨드가 성공한 직후, 그 시점의 응답을 JSON으로 그대로 얼려 저장한다.
    // replay는 이 스냅샷을 역직렬화해 반환하고, 커맨드를 다시 실행하지 않는다.
    public void attachResponseSnapshot(String responseSnapshot) {
        if (responseSnapshot == null || responseSnapshot.isBlank()) {
            throw new IllegalArgumentException("responseSnapshot은 필수입니다.");
        }
        this.responseSnapshot = responseSnapshot;
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

    public String getResponseSnapshot() {
        return responseSnapshot;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
