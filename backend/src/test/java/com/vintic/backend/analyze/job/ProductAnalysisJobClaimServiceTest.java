package com.vintic.backend.analyze.job;

import com.vintic.backend.analyze.job.ProductAnalysisJobClaimService.ClaimResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductAnalysisJobClaimServiceTest {

    @Mock
    private ProductAnalysisJobRepository jobRepository;

    private ProductAnalysisJobClaimService claimService;

    @BeforeEach
    void setUp() {
        claimService = new ProductAnalysisJobClaimService(jobRepository);
    }

    private ProductAnalysisJob jobWith(Long id, Long userId) {
        ProductAnalysisJob job = ProductAnalysisJob.create(userId, "uploads/a.jpg", "key");
        ReflectionTestUtils.setField(job, "id", id);
        return job;
    }

    @Test
    void 기존_job이_없으면_PENDING_job을_저장하고_created_true를_반환한다() {
        when(jobRepository.findByUserIdAndIdempotencyKey(1L, "key")).thenReturn(Optional.empty());

        ClaimResult result = claimService.claim(1L, "uploads/a.jpg", "key");

        assertThat(result.created()).isTrue();
        verify(jobRepository).saveAndFlush(any());
    }

    @Test
    void 기존_job이_있으면_그대로_반환하고_created_false다() {
        ProductAnalysisJob existing = jobWith(1L, 1L);
        when(jobRepository.findByUserIdAndIdempotencyKey(1L, "key")).thenReturn(Optional.of(existing));

        ClaimResult result = claimService.claim(1L, "uploads/a.jpg", "key");

        assertThat(result.created()).isFalse();
        assertThat(result.job()).isSameAs(existing);
        verify(jobRepository, never()).saveAndFlush(any());
    }

    @Test
    void 저장중_UNIQUE_충돌이면_ConflictException으로_원인을_보존해_던진다() {
        DataIntegrityViolationException original = new DataIntegrityViolationException("dup");
        when(jobRepository.findByUserIdAndIdempotencyKey(1L, "key")).thenReturn(Optional.empty());
        when(jobRepository.saveAndFlush(any())).thenThrow(original);

        assertThatThrownBy(() -> claimService.claim(1L, "uploads/a.jpg", "key"))
                .isInstanceOf(ProductAnalysisJobClaimConflictException.class)
                .hasCause(original);
    }

    @Test
    void 충돌_이후_재조회에_성공하면_기존_job을_반환한다() {
        ProductAnalysisJob existing = jobWith(1L, 1L);
        RuntimeException original = new DataIntegrityViolationException("dup");
        when(jobRepository.findByUserIdAndIdempotencyKey(1L, "key")).thenReturn(Optional.of(existing));

        ProductAnalysisJob result = claimService.resolveAfterConflict(1L, "key", original);

        assertThat(result).isSameAs(existing);
    }

    // 핵심 확인 대상: UNIQUE 충돌 이후 재조회에서도 job을 못 찾으면(멱등 충돌이 아닌 다른 원인),
    // "리소스가 없다"(ProductAnalysisJobNotFoundException/404)로 바꿔치기하지 않고 원래
    // DataIntegrityViolationException을 그대로 전파해야 한다 - 이건 예상하지 못한 DB 제약
    // 위반/정합성 문제이지 사용자가 존재하지 않는 리소스를 요청한 상황이 아니다.
    @Test
    void 충돌_이후_재조회도_실패하면_원래_예외가_그대로_전파되고_NotFound로_바뀌지_않는다() {
        RuntimeException original = new DataIntegrityViolationException("dup");
        when(jobRepository.findByUserIdAndIdempotencyKey(1L, "key")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> claimService.resolveAfterConflict(1L, "key", original))
                .isSameAs(original)
                .isNotInstanceOf(ProductAnalysisJobNotFoundException.class);
    }
}
