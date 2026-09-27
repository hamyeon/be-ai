package com.vintic.backend.analyze.queue;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

// application.yml의 analysis.stream.* 값을 바인딩한다. Stream 키/Consumer Group 이름을
// 코드에 하드코딩하지 않고 설정으로 분리해, 환경별로 바꾸거나 재배포 없이 조정할 수 있게 한다.
@Component
@ConfigurationProperties(prefix = "analysis.stream")
@Getter
@Setter
public class AnalysisStreamProperties {

    private String key = "ai:analysis:requests";
    private String group = "ai-analysis-workers";
    private String consumerPrefix = "worker";

    // 최종 실패(VISION_FAILED)를 남기는 전용 Stream. 별도 Consumer Group 없이 순수 append-only
    // 로그로 둔다 - 누가 언제 소비할지는 이번 스코프 밖(관측/알림 파이프라인 연결은 AI팀/운영
    // 쪽과 협의 필요, VisionFailureStreamRecorder 참고).
    private String failureKey = "ai:analysis:failures";

    // 종료 신호 후 진행 중인 작업을 기다려줄 최대 시간. StreamMessageListenerContainer.stop()/
    // stop(Runnable) 어느 쪽도 진행 중인 onMessage() 호출을 기다려주지 않는다는 것을 실측으로
    // 확인했다 - RedisStreamConsumerConfig가 이 값만큼 별도로 대기한다. 기본값(200s)은
    // analysis.vision.overall-timeout-ms(180s, Vision 처리 강제 상한) + 여유다. 이 시간 안에도
    // 못 끝나면 ACK되지 않은 채로 남아 다음 Worker가 회수한다(정확성은 이미 보장돼 있으므로
    // 이 값은 "불필요한 재작업을 줄이는" 용도다). docker-compose.yml의 stop_grace_period가 이
    // 값보다 짧으면 Docker가 먼저 SIGKILL을 보내 이 대기 자체가 무의미해진다 - 반드시 함께 맞춘다.
    private long shutdownGracePeriodMs = 200_000L;
}
