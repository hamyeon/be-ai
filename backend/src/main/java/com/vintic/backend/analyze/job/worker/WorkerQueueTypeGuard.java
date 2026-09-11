package com.vintic.backend.analyze.job.worker;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

// experiment-worker가 analysis.job.queue.type=sqs가 아닌 채로 뜨면 SqsWorkerClientConfig/
// SqsAnalysisJobPoller/SqsAnalysisJobPollerLifecycle(전부 @ConditionalOnProperty(type=sqs))이
// 조용히 빈으로 등록되지 않는다 - 그러면 이 프로세스를 살려두는 non-daemon 스레드가 하나도
// 없어 web-application-type=none과 맞물려 기동 직후 에러 로그 하나 없이 정상 종료돼버린다
// (Day5 2단계에서 실제로 재현/확인한 결함). Docker에서는 이게 "정상 종료된 컨테이너"로 보여
// 재시작만 반복하고 원인을 알기 어렵다.
//
// analysis.job.queue.type이 빈 값이면 QueuePublisher(API 쪽)는 이미 fail-fast하도록
// 돼 있다(application.yml 주석 참고) - 같은 원칙을 Worker 쪽에도 적용한다. poller 자체의
// timeout/stale/visibility 로직은 전혀 건드리지 않는다 - 기동 시점 설정 검증만 한다.
@Component
@Profile("experiment-worker")
public class WorkerQueueTypeGuard {

    private final String queueType;

    public WorkerQueueTypeGuard(@Value("${analysis.job.queue.type:}") String queueType) {
        this.queueType = queueType;
    }

    @PostConstruct
    public void assertSqsConfigured() {
        if (!"sqs".equals(queueType)) {
            throw new IllegalStateException(
                    "experiment-worker는 analysis.job.queue.type=sqs가 필수입니다 - 현재 값: '" + queueType
                            + "'. 이 설정이 없으면 SqsAnalysisJobPoller/PollerLifecycle 빈이 등록되지 않아 "
                            + "프로세스가 아무 작업도 하지 않고 즉시 종료됩니다.");
        }
    }
}
