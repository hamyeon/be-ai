package com.vintic.backend.analyze.queue;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

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
    // 분당 토큰 한도다 - 한 건 약 15초·9천 토큰이면 1건씩만 처리해도 분당 약 3.6만 토큰이라, 한도가
    // 30,000이면 늘려도 429 재시도 대기만 는다. 한도를 확인하고 그만큼만 올린다.
    private int concurrency = 1;

    // XADD 때 스트림을 이 길이 근처로 자른다(MAXLEN ~). ACK해도 엔트리는 지워지지 않아서 두면 계속 쌓인다.
    // 0 이하면 자르지 않는다. 아직 처리 전인 메시지까지 잘리지 않도록 대기열보다 넉넉히 둔다.
    private long maxLength = 10_000;

    private Recovery recovery = new Recovery();

    @Getter
    @Setter
    public static class Recovery {

        // 이 시간 넘게 ACK되지 않은 메시지를 회수한다. 정상 분석(수십 초)에 429 재시도 대기까지 더해도
        // 넘지 않을 만큼 길게 잡는다 - 짧으면 아직 처리 중인 분석을 실패로 정리해버린다.
        private Duration pendingIdleTimeout = Duration.ofMinutes(10);

        // 회수 작업 한 번에 살펴볼 미처리 메시지 수
        private int batchSize = 50;

        // 미처리 메시지가 없고 이 시간 넘게 아무것도 읽지 않은 Consumer는 그룹에서 지운다.
        // 인스턴스가 재시작할 때마다 새 이름으로 Consumer가 생겨서, 두면 XINFO에 죽은 이름이 쌓인다.
        private Duration idleConsumerTimeout = Duration.ofDays(1);
    }
}
