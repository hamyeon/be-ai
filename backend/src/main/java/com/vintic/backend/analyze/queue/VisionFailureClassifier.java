package com.vintic.backend.analyze.queue;

import com.vintic.backend.common.exception.AiApiException;
import com.vintic.backend.common.exception.AiResponseFormatException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;

// Vision 처리 실패가 다시 시도해볼 만한지 판정하는 최소 경계.
//
// 벤더 클라이언트가 이미 단계별로 429/5xx/네트워크 오류를 재시도하므로(OpenAiVisionClient 최대 5회 /
// Claude SDK anthropic.max-retries 4회), 여기서
// "재시도 가능"으로 판정해도 그 재시도 루프를 다시 반복시키는 게 아니다 - 이 판정은 오직
// "이 메시지를 ACK하지 않고 PEL에 남겨 나중에(AnalysisStreamRecoveryScheduler가 minIdleTime
// 간격으로) 다시 넘겨줄 가치가 있는가"만 결정한다(AnalysisTaskConsumer 참고). 재시도 횟수
// 상한은 Redis 배달 횟수가 아니라 ProductAnalysisSession.visionFailureAttemptCount를
// analysis.vision.max-vision-failure-attempts(maxVisionFailureAttempts)와 비교해 잰다 -
// Redis 배달 횟수(deliveryCount)는 로그용일 뿐이다.
//
// AI팀의 예외 타입이 아직 확정되지 않았다 - 이 클래스가 유일한 연결 지점이다. 새 예외 타입이
// 나오면 여기 분류만 갱신하면 된다. 분류 불가능한 예외는 안전한 쪽(재시도 가능)으로 본다 -
// Vision 실패 횟수 상한이 어차피 무한 재시도를 막아준다.
@Component
public class VisionFailureClassifier {

    // executor 포화(RejectedExecutionException)는 Vision을 아예 시도조차 못한 순수 로컬 용량
    // 문제다 - "분석을 시도했지만 반복해서 실패했다"와 다르다. Vision 실패 횟수 상한
    // (analysis.vision.max-vision-failure-attempts)은 후자를 다루기 위한 것이라, 이 경우는 그 상한과
    // 무관하게 항상 재시도해야 한다 - 상한에 걸려 VISION_FAILED로 확정되면 실제로는 아무
    // 이미지도 분석하지 않았는데 "분석 실패"로 잘못 기록되고 실패 Stream에도 잘못 발행된다.
    // AnalysisTaskConsumer.handleVisionFailure()가 isRetryable()보다 먼저 이 메서드를 확인한다.
    public boolean isLocalOverload(RuntimeException e) {
        return e.getCause() instanceof RejectedExecutionException;
    }

    public boolean isRetryable(RuntimeException e) {
        Throwable cause = e.getCause();

        // Future.get() 타임아웃(처리 상한 초과)은 OpenAI 응답과 무관할 수도 있는 로컬/타이밍
        // 문제지만, executor 포화와 달리 Vision 호출 자체는 시작됐었다 - isLocalOverload()와
        // 달리 Vision 실패 횟수 상한의 적용 대상으로 둔다(반복해서 상한을 넘기면 최종 실패로 확정된다).
        if (cause instanceof TimeoutException) {
            return true;
        }

        if (e instanceof AiApiException) {
            if (cause instanceof HttpStatusCodeException statusError) {
                int status = statusError.getStatusCode().value();
                // 429(분당 한도)/5xx(서버 일시 장애)만 재시도 가치가 있다. 그 외 4xx(잘못된
                // 요청·인증 실패 등)는 요청 자체가 잘못됐다는 뜻이라 다시 불러도 같은 결과다.
                return status == 429 || statusError.getStatusCode().is5xxServerError();
            }
            if (cause instanceof ResourceAccessException) {
                return true; // 네트워크 연결 실패 - 일시적일 수 있다
            }
        }

        // AiResponseFormatException(응답 파싱/스키마 실패)을 "항상 같은 결과가 반복된다"고
        // 단정하지 않는다 - 모델 응답이 흔들려 우연히 깨졌을 가능성을 배제할 수 없다. Vision
        // 실패 횟수 상한이 있으므로 재시도 대상으로 둬도 무한정 반복되지 않는다.
        return true;
    }
}
