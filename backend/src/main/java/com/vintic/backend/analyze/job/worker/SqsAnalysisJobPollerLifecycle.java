package com.vintic.backend.analyze.job.worker;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Duration;

// Day3 3단계 shutdown coordinator. SqsAnalysisJobPoller의 시작/정지 신호를 Spring의 기존
// @PreDestroy lifecycle에 연결한다 - 별도 shutdown hook을 새로 만들지 않는다.
//
// @PreDestroy는 stop()(신규 polling 중단 신호) 다음 awaitTermination(timeout)(이미 받은
// 작업의 완료를 bounded wait)을 순서대로 호출한다. 한 번의 @PreDestroy 호출(= 한 번의
// SIGTERM -> ApplicationContext.close())로 신호와 대기가 모두 끝나므로, 같은 종료 명령을
// 두 번 보내야 멈추는 구조가 아니다.
//
// SqsClient를 먼저 닫지 않는 이유: 이 빈은 SqsAnalysisJobPoller(그리고 그 Poller는
// SqsClient)를 생성자로 주입받는다. Spring의 싱글톤 소멸 순서는 "의존하는 빈을 의존 대상보다
// 먼저 파괴"하므로, 이 빈의 @PreDestroy(awaitTermination 포함)가 완전히 끝난 뒤에야
// SqsAnalysisJobPoller, 그리고 그 다음 SqsClient(SqsWorkerClientConfig의 자동 close)가
// 파괴된다 - 별도 순서 제어 코드 없이 Spring 기본 lifecycle만으로 "in-flight 작업보다
// SqsClient를 먼저 닫지 않음"이 보장된다.
//
// timeout 기본값(75초)은 docs/infra-sprint/contracts.md의 Timeout 관계(정상 Worker 최대
// 처리시간 70초 < 이 값 < docker stop timeout 80~90초)에서 역산했다 - 정상 처리 1건은
// 충분히 기다리되, docker가 SIGKILL하기 전에 스스로 대기를 끝낸다.
//
// 20초(long polling 최대 대기)와 70초(정상 처리 최대시간)는 더하지 않는다 - stop()이
// receiveMessage 대기 중에 오면 그 응답은 처리하지 않고 버리므로(SqsAnalysisJobPoller.pollOnce
// 참고) 이미 Handler가 실행 중이 아닌 한 최대 20초만 기다리면 되고, 이미 Handler가 실행
// 중이라면 receiveMessage 대기는 이미 끝난 상태이므로 남은 처리시간(최대 70초)만 기다리면
// 된다 - 두 대기가 겹치는 경로가 없다.
@Component
@Profile("experiment-worker")
@ConditionalOnProperty(prefix = "analysis.job.queue", name = "type", havingValue = "sqs")
@Slf4j
public class SqsAnalysisJobPollerLifecycle {

    private final SqsAnalysisJobPoller poller;
    private final Duration shutdownTimeout;

    public SqsAnalysisJobPollerLifecycle(
            SqsAnalysisJobPoller poller,
            @Value("${analysis.worker.shutdown.timeout-ms:75000}") long shutdownTimeoutMs
    ) {
        this.poller = poller;
        this.shutdownTimeout = Duration.ofMillis(shutdownTimeoutMs);
    }

    @PostConstruct
    public void startPolling() {
        poller.start();
    }

    @PreDestroy
    public void stopPolling() {
        log.info("SQS 분석 작업 poller 종료를 시작합니다 - 신규 polling을 중단하고 in-flight 작업 완료를 최대 {}ms 대기합니다.",
                shutdownTimeout.toMillis());
        poller.stop();
        boolean drained = poller.awaitTermination(shutdownTimeout);
        if (!drained) {
            log.warn("SQS 분석 작업 poller가 {}ms 안에 종료되지 않았습니다 - in-flight 작업이 남아있을 수 있습니다.",
                    shutdownTimeout.toMillis());
        } else {
            log.info("SQS 분석 작업 poller가 정상적으로 종료되었습니다.");
        }
    }
}
