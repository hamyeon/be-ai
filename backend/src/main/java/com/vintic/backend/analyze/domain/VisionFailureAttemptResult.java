package com.vintic.backend.analyze.domain;

// VisionAttemptCoordinator.incrementFailureAttempt()의 결과. attemptCount는 outcome이
// COMMITTED일 때만 의미가 있다(이번 증가 이후의 값) - 그 외에는 0으로 둔다.
public record VisionFailureAttemptResult(VisionAttemptOutcome outcome, int attemptCount) {
}
