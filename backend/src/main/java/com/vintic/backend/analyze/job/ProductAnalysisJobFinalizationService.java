package com.vintic.backend.analyze.job;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Handler와 분리된 별도 Spring Service 프록시. completeIfOwned/failIfOwned(각각 자체
// @Transactional을 가진 Repository 조건부 UPDATE)와, 성공 시의 AnalysisResult 저장을 하나의
// 외부 트랜잭션으로 묶는다 - Handler가 이 빈을 주입받아 호출해야 프록시를 거치므로,
// ProductAnalysisJobClaimService와 같은 이유로 self-invocation 문제가 없다.
//
// complete()에서 결과 저장(saveAndFlush)이 실패하면(예: UNIQUE 위반, DB 오류) 예외가 그대로
// 전파되어 이 메서드의 트랜잭션 전체가 롤백된다 - 방금 반영된 completeIfOwned의 COMPLETED
// 전이도 함께 취소된다. 이 메서드는 그 예외를 여기서 삼키지 않는다 - 호출자인
// SqsAnalysisJobHandler.handle()의 바깥 catch-all이 이를 RETAIN으로 처리한다(메시지를
// 지우지 않고 유지해 다음 배달에서 다시 시도할 수 있게 한다).
@Service
@Slf4j
public class ProductAnalysisJobFinalizationService {

    public enum FinalizeOutcome {
        COMPLETED,
        LEASE_LOST
    }

    public enum FailOutcome {
        FAILED,
        LEASE_LOST
    }

    private final ProductAnalysisJobRepository jobRepository;
    private final AnalysisResultRepository resultRepository;

    public ProductAnalysisJobFinalizationService(
            ProductAnalysisJobRepository jobRepository,
            AnalysisResultRepository resultRepository
    ) {
        this.jobRepository = jobRepository;
        this.resultRepository = resultRepository;
    }

    @Transactional
    public FinalizeOutcome complete(Long analysisId, String workerId, String rawResult) {
        int affected = jobRepository.completeIfOwned(analysisId, workerId);
        if (affected == 0) {
            log.warn("완료 처리 시점에 lease를 상실했습니다(다른 Worker가 재선점했거나 이미 종료됨) - " +
                    "결과를 저장하지 않습니다. analysisId={}, workerId={}", analysisId, workerId);
            return FinalizeOutcome.LEASE_LOST;
        }
        resultRepository.saveAndFlush(AnalysisResult.create(analysisId, rawResult));
        return FinalizeOutcome.COMPLETED;
    }

    @Transactional
    public FailOutcome fail(Long analysisId, String workerId) {
        int affected = jobRepository.failIfOwned(analysisId, workerId);
        if (affected == 0) {
            log.warn("실패 처리 시점에 lease를 상실했습니다(다른 Worker가 재선점했거나 이미 종료됨). " +
                    "analysisId={}, workerId={}", analysisId, workerId);
            return FailOutcome.LEASE_LOST;
        }
        return FailOutcome.FAILED;
    }
}
