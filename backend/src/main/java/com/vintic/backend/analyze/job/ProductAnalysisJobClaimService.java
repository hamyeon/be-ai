package com.vintic.backend.analyze.job;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// (userId, idempotencyKey) claim(=PENDING job 생성)과, UNIQUE 충돌 이후 조회를 각각 별도
// 트랜잭션으로 커밋하는 계층이다. bid.service.IdempotencyClaimService와 동일한 이유로 분리했다 -
// 이 빈의 메서드는 반드시 ProductAnalysisJobService처럼 이 빈을 주입받는 다른 빈에서 호출해야
// 한다. 같은 클래스 안에서 this로 서로를 호출하면 Spring 프록시를 우회해 @Transactional이
// 적용되지 않는다.
@Service
public class ProductAnalysisJobClaimService {

    private final ProductAnalysisJobRepository jobRepository;

    public ProductAnalysisJobClaimService(ProductAnalysisJobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    public record ClaimResult(ProductAnalysisJob job, boolean created) {
    }

    // (userId, idempotencyKey) 기존 job이 있으면 그대로 반환(재발행하지 않을 신호로 created=false).
    // 없으면 PENDING job을 저장·flush하고 커밋한다 - 이 트랜잭션이 끝나야 호출부가 publish로
    // 넘어간다(커밋 전 publish를 막기 위해 claim과 publish를 서로 다른 트랜잭션/메서드로 분리).
    @Transactional
    public ClaimResult claim(Long userId, String objectKey, String idempotencyKey) {
        return jobRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey)
                .map(existing -> new ClaimResult(existing, false))
                .orElseGet(() -> {
                    ProductAnalysisJob job = ProductAnalysisJob.create(userId, objectKey, idempotencyKey);
                    try {
                        jobRepository.saveAndFlush(job);
                    } catch (DataIntegrityViolationException e) {
                        // 이 시점 이후로는 같은 트랜잭션에서 추가 DB 작업을 하지 않는다. 그대로 던져서
                        // 트랜잭션을 롤백시키고, 조회는 별도 트랜잭션(resolveAfterConflict)에 맡긴다.
                        throw new ProductAnalysisJobClaimConflictException(e);
                    }
                    return new ClaimResult(job, true);
                });
    }

    // UNIQUE 충돌 시점에 이긴 트랜잭션은 이미 commit되어 있음이 보장된다(InnoDB가 같은 unique
    // key의 두 번째 INSERT를 첫 트랜잭션의 commit/rollback까지 블로킹하기 때문). 재조회로도 못
    // 찾으면(멱등 충돌이 아닌 다른 원인) 원래 예외를 그대로 다시 던진다.
    @Transactional
    public ProductAnalysisJob resolveAfterConflict(Long userId, String idempotencyKey, RuntimeException originalConflict) {
        return jobRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey)
                .orElseThrow(() -> originalConflict);
    }
}
