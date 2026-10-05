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

    // 인스턴스 하나가 동시에 처리하는 분석 수(#106). Consumer를 이 수만큼 띄우고 각자 한 건씩 읽는다.
    //
    // 늘리면 대기열은 빨리 빠지지만 Vision 호출이 그만큼 동시에 나간다. 병목은 스레드가 아니라 벤더의
    // 분당 토큰 한도다 - Claude(Sonnet 5) 분석 한 건은 약 11초에 2만 토큰(사진 3~6장)이라 1건씩이면
    // 분당 약 11만 토큰이다. 2026-09-29 조직 한도는 입력 50만/분이다. 한도를 넘기면 429 재시도 대기만
    // 는다. 한도를 확인하고 그만큼만 올린다(동시 처리 수만큼 비용도 빨리 나간다).
    private int concurrency = 1;

    // XADD 때 스트림을 이 길이 근처로 자른다(MAXLEN ~). ACK해도 엔트리는 지워지지 않아서 두면 계속 쌓인다.
    // 0 이하면 자르지 않는다. 아직 처리 전인 메시지까지 잘리지 않도록 대기열보다 넉넉히 둔다.
    private long maxLength = 10_000;

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
