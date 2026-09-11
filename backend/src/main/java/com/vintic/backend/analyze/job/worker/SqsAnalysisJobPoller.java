package com.vintic.backend.analyze.job.worker;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

import java.time.Duration;
import java.util.List;

// SQS long polling 루프만 담당한다. start()/stop()/awaitTermination()은 모두 idempotent -
// 정지 시점의 in-flight 메시지 drain 타이밍/timeout 예산 결정은 3단계 shutdown coordinator가
// stop() + awaitTermination(budget)을 직접 호출해 정한다. 여기서는 임시 timeout을 두지 않는다.
//
// stop()은 신호만 내리므로, 현재 receiveMessage 호출이 설정된 waitTimeSeconds(기본 20초) 동안
// 블로킹 중이면 그 호출이 끝나야 스레드가 다음 판단을 할 수 있다. receiveMessage가 반환된 직후,
// Handler를 부르기 전에 running을 다시 확인한다 - stop() 신호가 그 대기 중에 왔다면 방금
// 받은 메시지는 처리도 삭제도 하지 않고 그대로 둔다(Visibility Timeout이 지나면 다시 노출되어
// 유실되지 않는다). 이미 Handler 호출을 시작한 메시지만 in-flight로 보고 끝까지 처리한다 -
// 그래서 종료 시간은 "20초(수신 대기 중) 또는 남은 처리시간(최대 70초, 이미 처리 중)" 중
// 하나이지 둘을 더한 값이 아니다.
@Component
@Profile("experiment-worker")
@ConditionalOnProperty(prefix = "analysis.job.queue", name = "type", havingValue = "sqs")
@Slf4j
public class SqsAnalysisJobPoller {

    private static final int MAX_NUMBER_OF_MESSAGES = 1;

    private final SqsClient sqsClient;
    private final SqsAnalysisJobHandler handler;
    private final String queueUrl;
    private final int waitTimeSeconds;
    private final int visibilityTimeoutSeconds;

    private volatile Thread pollingThread;
    private volatile boolean running;

    public SqsAnalysisJobPoller(
            SqsClient sqsClient,
            SqsAnalysisJobHandler handler,
            @Value("${analysis.job.queue.sqs.queue-url}") String queueUrl,
            @Value("${analysis.worker.poll.wait-time-seconds:20}") int waitTimeSeconds,
            @Value("${analysis.worker.visibility-timeout-seconds:90}") int visibilityTimeoutSeconds
    ) {
        this.sqsClient = sqsClient;
        this.handler = handler;
        this.queueUrl = queueUrl;
        this.waitTimeSeconds = waitTimeSeconds;
        this.visibilityTimeoutSeconds = visibilityTimeoutSeconds;
    }

    public synchronized void start() {
        if (pollingThread != null) {
            return;
        }
        running = true;
        pollingThread = new Thread(this::pollLoop, "sqs-analysis-job-poller");
        pollingThread.start();
    }

    public synchronized void stop() {
        running = false;
    }

    public boolean awaitTermination(Duration timeout) {
        Thread thread = pollingThread;
        if (thread == null) {
            return true;
        }
        try {
            thread.join(timeout.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return !thread.isAlive();
    }

    private void pollLoop() {
        log.info("SQS 분석 작업 poller를 시작합니다.");
        while (running) {
            try {
                pollOnce();
            } catch (Exception e) {
                log.error("SQS poll 루프에서 예상하지 못한 오류가 발생했습니다.", e);
            }
        }
        log.info("SQS 분석 작업 poller를 종료합니다.");
    }

    private void pollOnce() {
        List<Message> messages = sqsClient.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(MAX_NUMBER_OF_MESSAGES)
                .waitTimeSeconds(waitTimeSeconds)
                .visibilityTimeout(visibilityTimeoutSeconds)
                .attributeNamesWithStrings(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT.toString())
                .build()).messages();

        if (!running) {
            // stop()이 receiveMessage 대기 중에 왔다 - 방금 받은 메시지는 새 작업이므로
            // Handler를 부르지 않고 그대로 둔다. DeleteMessage도 하지 않으므로 Visibility
            // Timeout이 지나면 다른(또는 재시작된) Worker에게 다시 노출된다.
            return;
        }

        for (Message message : messages) {
            SqsAnalysisJobHandler.Outcome outcome = handler.handle(
                    new ReceivedQueueMessage(message.messageId(), message.body(), approximateReceiveCountOf(message)));
            if (outcome == SqsAnalysisJobHandler.Outcome.DELETE) {
                sqsClient.deleteMessage(DeleteMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .receiptHandle(message.receiptHandle())
                        .build());
            }
        }
    }

    // ApproximateReceiveCount는 요청한 system attribute이므로 정상적으로는 항상 응답에 실려
    // 온다. 누락되거나 정수로 파싱되지 않으면 null을 돌려줘 Handler가 "재시도 소진 여부를 알 수
    // 없음"으로 보수적으로 RETAIN하게 한다.
    private Integer approximateReceiveCountOf(Message message) {
        String raw = message.attributesAsStrings() == null
                ? null
                : message.attributesAsStrings().get(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT.toString());
        if (raw == null) {
            log.warn("ApproximateReceiveCount 속성이 응답에 없습니다 - messageId={}", message.messageId());
            return null;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            log.warn("ApproximateReceiveCount 파싱에 실패했습니다 - messageId={}, value={}", message.messageId(), raw);
            return null;
        }
    }
}
