package com.vintic.backend.analyze.domain;

// Vision 처리 시도(claim/reclaim/complete/fail)의 결과. AnalysisTaskConsumer와 PEL 회수
// 스케줄러가 이 값으로 XACK 여부를 결정한다. InvalidAnalysisStatusException 하나로는 "이미
// 끝나서 ACK해도 되는 상황"과 "다른 시도가 소유권을 가져가서 ACK하면 안 되는 상황"을 구분할 수
// 없어 별도 타입으로 뺐다.
public enum VisionAttemptOutcome {

    // 이번 시도가 반영됨(결과/상태 변경이 한 트랜잭션에 커밋됨) - ACK 대상.
    COMMITTED,

    // 이미 이 단계를 벗어난 최종 상태로 확인됨(다른 시도가 이미 끝냈거나, DB 완료 후 ACK만
    // 실패해 재전달된 경우) - 재처리 없이 ACK만 한다.
    ALREADY_FINALIZED,

    // 다른 처리 시도(재선점)가 이미 이 세션의 소유권을 가져감 - ACK 금지, 재작업하지 않는다.
    OWNERSHIP_LOST;

    // claim/reclaim/complete/fail 시도가 InvalidAnalysisStatusException으로 막혔을 때, 시도
    // 직전에 읽은 상태를 근거로 ALREADY_FINALIZED와 OWNERSHIP_LOST를 구분한다. QUEUED/
    // VISION_PROCESSING은 아직 이 단계 안에 있다는 뜻이라 "다른 시도가 진행 중/가져감"으로 보고,
    // 그 밖의 상태는 이미 이 단계를 벗어났다는 뜻이라 "이미 종료"로 본다.
    public static VisionAttemptOutcome fromGuardFailure(AnalysisStatus statusBeforeAttempt) {
        boolean stillInVisionStage = statusBeforeAttempt == AnalysisStatus.QUEUED
                || statusBeforeAttempt == AnalysisStatus.VISION_PROCESSING;
        return stillInVisionStage ? OWNERSHIP_LOST : ALREADY_FINALIZED;
    }
}
