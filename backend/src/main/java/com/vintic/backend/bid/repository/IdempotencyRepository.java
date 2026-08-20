package com.vintic.backend.bid.repository;

import com.vintic.backend.bid.domain.Idempotency;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface IdempotencyRepository extends JpaRepository<Idempotency, Long> {

    Optional<Idempotency> findByUserIdAndOperationScopeAndIdempotencyKey(
            Long userId, String operationScope, String idempotencyKey
    );
}
