package com.vintic.backend.analyze.job.queue;

public interface QueuePublisher {

    void publish(AnalysisJobQueueMessage message);
}
