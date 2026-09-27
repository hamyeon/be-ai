package com.vintic.backend.analyze.queue;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

// Vision 전체 처리(3단계 x 재시도)에 Consumer가 기다려주는 최대 시간. OpenAiVisionClient의
// 재시도(단계별 최대 5회, 백오프/서버 힌트 대기 포함)는 이 상한을 모르고 동작하므로, 여기서
// 정한 시간을 넘기면 AnalysisTaskConsumer가 강제로 끊어 VISION_FAILED로 처리한다.
// 기본값(180s)은 openai.vision.http.read-timeout-ms(단일 호출 상한, 기본 30s)와
// OpenAiVisionClient.MAX_ATTEMPTS(5)를 근거로 잡은 값이다 - docs/ai-async-analysis.md 참고.
// PEL 회수 minIdleTime(analysis.stream.recovery.min-idle-time-ms)은 이 값 + 여유시간으로 도출한다.
//
// executorQueueCapacity: overallTimeoutMs가 지나 Future.get()을 포기해도, RestTemplate의
// readTimeout이 걸린 소켓 I/O는 Thread.interrupt()로 즉시 끊어지지 않는다(java.net 블로킹
// 소켓은 인터럽트에 반응하지 않고, read-timeout-ms가 지나야 스스로 풀린다) - 그래서 타임아웃난
// 시도도 executor 스레드를 한동안(최악의 경우 read-timeout-ms x 재시도 횟수만큼) 붙잡고 있을 수
// 있다. 풀+큐를 무한정 늘리지 않고 유한하게 둬서, 좀비 시도가 쌓였을 때 새 메시지가 뒤에서
// 무한정 대기하지 않고 즉시 RejectedExecutionException으로 실패하게 한다(AnalysisTaskConsumer 참고).
@Component
@ConfigurationProperties(prefix = "analysis.vision")
@Getter
@Setter
public class AnalysisVisionProcessingProperties {

    private long overallTimeoutMs = 180_000L;
    private int executorPoolSize = 4;
    private int executorQueueCapacity = 8;

    // 재시도 가능하다고 판정된(VisionFailureClassifier) 실패를 최대 몇 번까지 재시도할지.
    // Redis 배달 횟수가 아니라 ProductAnalysisSession.visionFailureAttemptCount(진짜로 Vision을
    // 시도했다가 실패한 횟수만 세는 별도 카운트)를 기준으로 삼는다 - executor 포화처럼 Vision을
    // 아예 시도조차 못한 재전달은 이 상한을 소모하지 않는다(AnalysisTaskConsumer 참고). 이 값을
    // 넘기면 재시도 가능한 오류라도 최종 실패로 기록한다("재시도 횟수 초과").
    private int maxVisionFailureAttempts = 5;
}
