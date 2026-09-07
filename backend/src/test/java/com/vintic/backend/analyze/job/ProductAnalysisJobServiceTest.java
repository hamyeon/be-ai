package com.vintic.backend.analyze.job;

import com.vintic.backend.analyze.job.ProductAnalysisJobClaimService.ClaimResult;
import com.vintic.backend.analyze.job.queue.AnalysisJobQueueMessage;
import com.vintic.backend.analyze.job.queue.QueuePublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// ProductAnalysisJobService의 조건부 UPDATE 0건 처리(Worker 선점 시 덮어쓰지 않음)와 멱등
// claim/충돌 분기를 Repository/QueuePublisher를 모두 mock해서 결정적으로 검증한다. 실제
// 동시 race 타이밍은 여기서 재현하지 않는다 - "markQueued/markPublishFailed가 0건을 반환했을
// 때 서비스가 그 결과를 덮어쓰지 않고 실제 상태를 그대로 반환하는지"만 결정적으로 확인한다.
@ExtendWith(MockitoExtension.class)
class ProductAnalysisJobServiceTest {

    @Mock
    private ProductAnalysisJobClaimService claimService;

    @Mock
    private ProductAnalysisJobRepository jobRepository;

    @Mock
    private QueuePublisher queuePublisher;

    private ProductAnalysisJobService service;

    @BeforeEach
    void setUp() {
        service = new ProductAnalysisJobService(claimService, jobRepository, queuePublisher);
    }

    private ProductAnalysisJob jobWith(Long id, AnalysisJobStatus status) {
        ProductAnalysisJob job = ProductAnalysisJob.create(1L, "uploads/a.jpg", "key");
        ReflectionTestUtils.setField(job, "id", id);
        ReflectionTestUtils.setField(job, "status", status);
        return job;
    }

    @Test
    void publish가_성공하면_markQueued를_호출하고_최종_상태를_반환한다() {
        ProductAnalysisJob created = jobWith(1L, AnalysisJobStatus.PENDING);
        when(claimService.claim(1L, "uploads/a.jpg", "key")).thenReturn(new ClaimResult(created, true));
        when(jobRepository.findById(1L)).thenReturn(Optional.of(jobWith(1L, AnalysisJobStatus.QUEUED)));

        ProductAnalysisJob result = service.submit(1L, "uploads/a.jpg", "key");

        verify(queuePublisher).publish(AnalysisJobQueueMessage.forJob(1L));
        verify(jobRepository).markQueued(1L);
        verify(jobRepository, never()).markPublishFailed(any());
        assertThat(result.getStatus()).isEqualTo(AnalysisJobStatus.QUEUED);
    }

    @Test
    void publish가_실패하면_markPublishFailed를_호출하고_최종_상태를_반환한다() {
        ProductAnalysisJob created = jobWith(1L, AnalysisJobStatus.PENDING);
        when(claimService.claim(1L, "uploads/a.jpg", "key")).thenReturn(new ClaimResult(created, true));
        doThrow(new RuntimeException("SQS timeout")).when(queuePublisher).publish(any());
        when(jobRepository.findById(1L)).thenReturn(Optional.of(jobWith(1L, AnalysisJobStatus.PUBLISH_FAILED)));

        ProductAnalysisJob result = service.submit(1L, "uploads/a.jpg", "key");

        verify(jobRepository).markPublishFailed(1L);
        verify(jobRepository, never()).markQueued(any());
        assertThat(result.getStatus()).isEqualTo(AnalysisJobStatus.PUBLISH_FAILED);
    }

    // publish는 성공했지만 그 사이 Worker가 이미 PROCESSING으로 선점해 markQueued 조건부
    // UPDATE가 0건을 반영한 상황. 서비스는 이를 실패로 취급하지 않고 실제 DB 상태(PROCESSING)를
    // 그대로 반환해야 한다 - QUEUED로 덮어쓰면 안 된다.
    @Test
    void publish_성공_후_Worker가_이미_선점했으면_PROCESSING을_그대로_반환한다() {
        ProductAnalysisJob created = jobWith(1L, AnalysisJobStatus.PENDING);
        when(claimService.claim(1L, "uploads/a.jpg", "key")).thenReturn(new ClaimResult(created, true));
        when(jobRepository.markQueued(1L)).thenReturn(0);
        when(jobRepository.findById(1L)).thenReturn(Optional.of(jobWith(1L, AnalysisJobStatus.PROCESSING)));

        ProductAnalysisJob result = service.submit(1L, "uploads/a.jpg", "key");

        assertThat(result.getStatus()).isEqualTo(AnalysisJobStatus.PROCESSING);
    }

    // SQS SendMessage가 실제로는 저장에 성공했지만 클라이언트가 타임아웃 등으로 예외를 받아
    // publish 실패로 오인한 상황(응답유실 race). 그 사이 Worker가 이미 PROCESSING으로 옮겼다면
    // markPublishFailed 조건부 UPDATE는 0건이고, 서비스는 PROCESSING을 그대로 반환해야 한다.
    @Test
    void publish_실패로_오인해도_Worker가_이미_선점했으면_PROCESSING을_그대로_반환한다() {
        ProductAnalysisJob created = jobWith(1L, AnalysisJobStatus.PENDING);
        when(claimService.claim(1L, "uploads/a.jpg", "key")).thenReturn(new ClaimResult(created, true));
        doThrow(new RuntimeException("응답 유실")).when(queuePublisher).publish(any());
        when(jobRepository.markPublishFailed(1L)).thenReturn(0);
        when(jobRepository.findById(1L)).thenReturn(Optional.of(jobWith(1L, AnalysisJobStatus.PROCESSING)));

        ProductAnalysisJob result = service.submit(1L, "uploads/a.jpg", "key");

        assertThat(result.getStatus()).isEqualTo(AnalysisJobStatus.PROCESSING);
    }

    @Test
    void 이미_존재하는_job이면_재발행하지_않고_그대로_반환한다() {
        ProductAnalysisJob existing = jobWith(1L, AnalysisJobStatus.COMPLETED);
        when(claimService.claim(1L, "uploads/a.jpg", "key")).thenReturn(new ClaimResult(existing, false));

        ProductAnalysisJob result = service.submit(1L, "uploads/a.jpg", "key");

        assertThat(result).isSameAs(existing);
        verifyNoInteractions(queuePublisher);
        verify(jobRepository, never()).markQueued(any());
        verify(jobRepository, never()).markPublishFailed(any());
    }

    @Test
    void claim이_UNIQUE_충돌을_던지면_resolveAfterConflict로_기존_job을_반환하고_publish하지_않는다() {
        DataIntegrityViolationException original = new DataIntegrityViolationException("dup");
        when(claimService.claim(1L, "uploads/a.jpg", "key"))
                .thenThrow(new ProductAnalysisJobClaimConflictException(original));
        ProductAnalysisJob existing = jobWith(1L, AnalysisJobStatus.PENDING);
        when(claimService.resolveAfterConflict(eq(1L), eq("key"), any())).thenReturn(existing);

        ProductAnalysisJob result = service.submit(1L, "uploads/a.jpg", "key");

        assertThat(result).isSameAs(existing);
        verifyNoInteractions(queuePublisher);
    }

    @Test
    void republishFailed_성공하면_markQueued를_호출한다() {
        when(jobRepository.findById(1L))
                .thenReturn(Optional.of(jobWith(1L, AnalysisJobStatus.PUBLISH_FAILED)))
                .thenReturn(Optional.of(jobWith(1L, AnalysisJobStatus.QUEUED)));
        when(jobRepository.markQueued(1L)).thenReturn(1);

        ProductAnalysisJob result = service.republishFailed(1L);

        verify(queuePublisher).publish(AnalysisJobQueueMessage.forJob(1L));
        verify(jobRepository).markQueued(1L);
        assertThat(result.getStatus()).isEqualTo(AnalysisJobStatus.QUEUED);
    }

    @Test
    void republishFailed_도중_Worker가_선점하면_PROCESSING을_그대로_반환한다() {
        when(jobRepository.findById(1L))
                .thenReturn(Optional.of(jobWith(1L, AnalysisJobStatus.PUBLISH_FAILED)))
                .thenReturn(Optional.of(jobWith(1L, AnalysisJobStatus.PROCESSING)));
        when(jobRepository.markQueued(1L)).thenReturn(0);

        ProductAnalysisJob result = service.republishFailed(1L);

        assertThat(result.getStatus()).isEqualTo(AnalysisJobStatus.PROCESSING);
    }

    @Test
    void republishFailed_publish가_실패하면_markQueued를_호출하지_않는다() {
        when(jobRepository.findById(1L)).thenReturn(Optional.of(jobWith(1L, AnalysisJobStatus.PUBLISH_FAILED)));
        doThrow(new RuntimeException("SQS timeout")).when(queuePublisher).publish(any());

        ProductAnalysisJob result = service.republishFailed(1L);

        verify(jobRepository, never()).markQueued(any());
        assertThat(result.getStatus()).isEqualTo(AnalysisJobStatus.PUBLISH_FAILED);
    }

    // PUBLISH_FAILED가 아닌 상태(QUEUED/PROCESSING/COMPLETED/PENDING)에서 republishFailed를
    // 호출해도 재발행하지 않고 현재 상태를 그대로 반환해야 한다 - 이미 정상 진행 중이거나 끝난
    // job을 실수로 다시 큐에 올리지 않기 위함이다.
    @Test
    void PUBLISH_FAILED가_아니면_publish하지_않고_현재_상태를_그대로_반환한다() {
        ProductAnalysisJob queuedJob = jobWith(1L, AnalysisJobStatus.QUEUED);
        when(jobRepository.findById(1L)).thenReturn(Optional.of(queuedJob));

        ProductAnalysisJob result = service.republishFailed(1L);

        assertThat(result.getStatus()).isEqualTo(AnalysisJobStatus.QUEUED);
        verify(queuePublisher, never()).publish(any());
        verify(jobRepository, never()).markQueued(any());
    }

    @Test
    void republishFailed_존재하지_않으면_ProductAnalysisJobNotFoundException을_던진다() {
        when(jobRepository.findById(999L)).thenReturn(Optional.empty());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.republishFailed(999L))
                .isInstanceOf(ProductAnalysisJobNotFoundException.class);
        verifyNoInteractions(queuePublisher);
    }
}
