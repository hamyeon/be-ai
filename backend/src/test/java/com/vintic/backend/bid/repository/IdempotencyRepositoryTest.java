package com.vintic.backend.bid.repository;

import com.vintic.backend.bid.domain.Idempotency;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
class IdempotencyRepositoryTest {

    @Autowired
    private IdempotencyRepository idempotencyRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void 같은_user_operationScope_key_조합은_두_번_저장할_수_없다() {
        idempotencyRepository.saveAndFlush(
                Idempotency.claim(2L, "PLACE_BID:15", "abc", "hash-1")
        );

        assertThatThrownBy(() ->
                idempotencyRepository.saveAndFlush(
                        Idempotency.claim(2L, "PLACE_BID:15", "abc", "hash-2")
                )
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 다른_user면_같은_operationScope_key_조합도_저장할_수_있다() {
        idempotencyRepository.saveAndFlush(
                Idempotency.claim(2L, "PLACE_BID:15", "abc", "hash-1")
        );

        idempotencyRepository.saveAndFlush(
                Idempotency.claim(3L, "PLACE_BID:15", "abc", "hash-1")
        );

        entityManager.flush();
    }

    @Test
    void 같은_user라도_다른_operationScope면_같은_key_조합도_저장할_수_있다() {
        idempotencyRepository.saveAndFlush(
                Idempotency.claim(2L, "PLACE_BID:15", "abc", "hash-1")
        );

        idempotencyRepository.saveAndFlush(
                Idempotency.claim(2L, "PLACE_BID:16", "abc", "hash-1")
        );

        entityManager.flush();
    }
}
