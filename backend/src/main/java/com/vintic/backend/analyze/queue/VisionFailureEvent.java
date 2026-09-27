package com.vintic.backend.analyze.queue;

// 실패 전용 Stream에 담기는 페이로드. analysisId가 소비자 쪽 멱등 처리의 유일한 근거다 -
// 이 이벤트는 at-least-once로 발행될 수 있으므로(VisionFailureStreamRecorder 참고), 소비자는
// 같은 analysisId를 여러 번 받아도 안전하게 무시할 수 있어야 한다.
public record VisionFailureEvent(
        Long analysisId,
        String failureStage,
        String failureMessage,
        long failedAtEpochMs
) {
}
