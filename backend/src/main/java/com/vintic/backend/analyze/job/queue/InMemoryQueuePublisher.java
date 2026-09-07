package com.vintic.backend.analyze.job.queue;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

// SQS 없이 로컬 개발/테스트에서 QueuePublisher를 쓸 수 있게 하는 프로세스 내 구현.
// analysis.job.queue.type=in-memory로 명시적으로 설정했을 때만 활성화된다 - 설정이 없으면
// 운영에서 SQS 대신 조용히 이걸로 대체되는 일이 없도록 기본값을 두지 않는다.
@Component
@ConditionalOnProperty(prefix = "analysis.job.queue", name = "type", havingValue = "in-memory")
public class InMemoryQueuePublisher implements QueuePublisher {

    private final List<AnalysisJobQueueMessage> published = new CopyOnWriteArrayList<>();

    @Override
    public void publish(AnalysisJobQueueMessage message) {
        published.add(message);
    }

    public List<AnalysisJobQueueMessage> getPublished() {
        return List.copyOf(published);
    }
}
