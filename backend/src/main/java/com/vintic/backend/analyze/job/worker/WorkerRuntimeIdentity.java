package com.vintic.backend.analyze.job.worker;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;

// 프로세스 식별자. 프로세스 시작 시 한 번만 계산해 보관한다.
// Day4부터는 로그(MDC) 표시뿐 아니라 product_analysis_jobs.worker_id의 lease/fencing
// 소유권 값으로도 이 workerId를 그대로 재사용한다(SqsAnalysisJobHandler 참고) - 별도의
// DB 전용 ID 체계를 만들지 않는다.
@Component
public class WorkerRuntimeIdentity {

    private final String workerId;
    private final String serverId;

    public WorkerRuntimeIdentity(
            @Value("${analysis.worker.id:}") String configuredWorkerId,
            @Value("${analysis.worker.server-id:}") String configuredServerId
    ) {
        this.workerId = configuredWorkerId.isBlank() ? UUID.randomUUID().toString() : configuredWorkerId;
        this.serverId = configuredServerId.isBlank() ? resolveHostName() : configuredServerId;
    }

    private static String resolveHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown-host";
        }
    }

    public String workerId() {
        return workerId;
    }

    public String serverId() {
        return serverId;
    }
}
