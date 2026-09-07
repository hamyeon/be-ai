package com.vintic.backend.analyze.job;

import com.vintic.backend.analyze.job.ProductAnalysisJobClaimService.ClaimResult;
import com.vintic.backend.analyze.job.queue.AnalysisJobQueueMessage;
import com.vintic.backend.analyze.job.queue.QueuePublisher;
import org.springframework.stereotype.Service;

// Controller의 얇은 진입점 대상 오케스트레이터 - 이 클래스 자체는 @Transactional을 갖지 않는다.
// 트랜잭션 경계는 ProductAnalysisJobClaimService(claim/resolveAfterConflict)와
// ProductAnalysisJobRepository의 조건부 UPDATE 메서드가 각각 스스로 갖는다. publish()는 SQS
// 네트워크 호출이라 DB 트랜잭션 밖(두 트랜잭션 사이)에서 실행되어야 커밋 전에 Worker가 메시지를
// 받는 일이 없고, 트랜잭션을 오래 붙들지도 않는다.
@Service
public class ProductAnalysisJobService {

    private final ProductAnalysisJobClaimService claimService;
    private final ProductAnalysisJobRepository jobRepository;
    private final QueuePublisher queuePublisher;

    public ProductAnalysisJobService(
            ProductAnalysisJobClaimService claimService,
            ProductAnalysisJobRepository jobRepository,
            QueuePublisher queuePublisher
    ) {
        this.claimService = claimService;
        this.jobRepository = jobRepository;
        this.queuePublisher = queuePublisher;
    }

    public ProductAnalysisJob submit(Long userId, String objectKey, String idempotencyKey) {
        ClaimResult claim;
        try {
            claim = claimService.claim(userId, objectKey, idempotencyKey);
        } catch (ProductAnalysisJobClaimConflictException e) {
            // 충돌한 이 요청은 publish하지 않는다 - 이긴 트랜잭션이 만든 job을 그대로 반환한다.
            return claimService.resolveAfterConflict(userId, idempotencyKey, (RuntimeException) e.getCause());
        }

        // 이미 존재하던 job(동일 key의 순차 재요청)이면 상태와 무관하게 재발행하지 않고 그대로 반환한다.
        if (!claim.created()) {
            return claim.job();
        }

        Long analysisId = claim.job().getId();
        boolean published = tryPublish(analysisId);

        if (published) {
            jobRepository.markQueued(analysisId);
        } else {
            jobRepository.markPublishFailed(analysisId);
        }

        // markQueued/markPublishFailed는 영향 row가 0건이어도(예: Worker가 먼저 PROCESSING으로
        // 선점) 예외를 던지지 않는다 - 항상 재조회해서 DB의 실제 최종 상태를 그대로 반환한다.
        return findByIdOrThrow(analysisId);
    }

    // PUBLISH_FAILED 상태의 job을 다시 발행하는 골격. 관리자 Controller/Scheduler는 이번 범위가
    // 아니라 아직 없다 - 이 메서드만 놓아둔다.
    //
    // publish 직전에 현재 상태를 한 번 확인해 PUBLISH_FAILED가 아니면 재발행하지 않는다 - 이미
    // QUEUED/PROCESSING/COMPLETED/PENDING인 job을 실수로 다시 큐에 올리지 않기 위함이다. 이 확인과
    // publish 사이에 Worker가 끼어드는 race까지 막지는 않는다 - SQS Standard 자체가 중복 전달을
    // 허용하고, 최종 중복 처리는 Day4 선점/fencing이 맡는다. 여기서는 조건부 UPDATE(markQueued)가
    // 그 사이 상태가 바뀐 경우를 0건으로 그냥 무시하는 정도로 충분하다.
    public ProductAnalysisJob republishFailed(Long analysisId) {
        ProductAnalysisJob job = findByIdOrThrow(analysisId);
        if (job.getStatus() != AnalysisJobStatus.PUBLISH_FAILED) {
            return job;
        }

        boolean published = tryPublish(analysisId);

        if (published) {
            // PUBLISH_FAILED -> QUEUED만 반영한다. Worker가 이미 PROCESSING으로 바꿨다면 0건이
            // 반영되고, 아래 재조회가 그 실제 상태(PROCESSING)를 그대로 돌려준다.
            jobRepository.markQueued(analysisId);
        }
        // publish 실패면 PUBLISH_FAILED로 유지 - 별도 UPDATE가 필요 없다.

        return findByIdOrThrow(analysisId);
    }

    // QueuePublisher.publish() 호출 범위만 감싼다 - DB 조회/저장 등 다른 코드의 예외까지
    // publish 실패로 흡수하지 않기 위해 이 메서드 밖에서는 catch하지 않는다.
    private boolean tryPublish(Long analysisId) {
        try {
            queuePublisher.publish(AnalysisJobQueueMessage.forJob(analysisId));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private ProductAnalysisJob findByIdOrThrow(Long analysisId) {
        return jobRepository.findById(analysisId)
                .orElseThrow(() -> new ProductAnalysisJobNotFoundException(
                        "존재하지 않는 분석 작업입니다. analysisId: " + analysisId));
    }
}
