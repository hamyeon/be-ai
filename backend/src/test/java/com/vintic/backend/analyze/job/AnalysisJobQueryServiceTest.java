package com.vintic.backend.analyze.job;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalysisJobQueryServiceTest {

    @Mock
    private ProductAnalysisJobRepository jobRepository;

    private AnalysisJobQueryService service;

    @BeforeEach
    void setUp() {
        service = new AnalysisJobQueryService(jobRepository);
    }

    private ProductAnalysisJob jobOwnedBy(Long userId) {
        ProductAnalysisJob job = ProductAnalysisJob.create(userId, "uploads/a.jpg", "key");
        ReflectionTestUtils.setField(job, "id", 1L);
        return job;
    }

    @Test
    void 본인_job이면_조회된다() {
        ProductAnalysisJob job = jobOwnedBy(10L);
        when(jobRepository.findById(1L)).thenReturn(Optional.of(job));

        ProductAnalysisJob result = service.getOwnedJob(1L, 10L);

        assertThat(result).isSameAs(job);
    }

    @Test
    void 다른_사용자의_job을_조회하면_AccessDenied가_발생한다() {
        ProductAnalysisJob job = jobOwnedBy(10L);
        when(jobRepository.findById(1L)).thenReturn(Optional.of(job));

        assertThatThrownBy(() -> service.getOwnedJob(1L, 999L))
                .isInstanceOf(ProductAnalysisJobAccessDeniedException.class);
    }

    @Test
    void 존재하지_않으면_NotFound가_발생한다() {
        when(jobRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getOwnedJob(1L, 10L))
                .isInstanceOf(ProductAnalysisJobNotFoundException.class);
    }
}
