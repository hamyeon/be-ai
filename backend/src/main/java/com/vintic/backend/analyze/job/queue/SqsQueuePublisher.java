package com.vintic.backend.analyze.job.queue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.common.exception.AnalysisQueueException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsClientBuilder;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.net.URI;

// LocalStack과 실제 AWS SQS를 모두 지원하는 단일 구현. endpoint-override가 설정되면(LocalStack)
// 그 엔드포인트로, 비어있으면(실제 AWS) 기본 AWS 엔드포인트로 붙는다. 자격증명은 하드코딩하지
// 않고 DefaultCredentialsProvider(EC2 Role 등 기본 체인)를 쓴다.
// analysis.job.queue.type=sqs로 명시적으로 설정했을 때만 활성화된다.
@Component
@ConditionalOnProperty(prefix = "analysis.job.queue", name = "type", havingValue = "sqs")
public class SqsQueuePublisher implements QueuePublisher {

    private final SqsClient sqsClient;
    private final String queueUrl;
    private final ObjectMapper objectMapper;

    public SqsQueuePublisher(
            @Value("${analysis.job.queue.sqs.queue-url}") String queueUrl,
            @Value("${analysis.job.queue.sqs.region}") String region,
            @Value("${analysis.job.queue.sqs.endpoint-override:}") String endpointOverride,
            ObjectMapper objectMapper
    ) {
        this.queueUrl = queueUrl;
        this.objectMapper = objectMapper;

        SqsClientBuilder builder = SqsClient.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create());
        if (endpointOverride != null && !endpointOverride.isBlank()) {
            builder.endpointOverride(URI.create(endpointOverride));
        }
        this.sqsClient = builder.build();
    }

    @Override
    public void publish(AnalysisJobQueueMessage message) {
        String body;
        try {
            body = objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new AnalysisQueueException("분석 작업 메시지를 직렬화하는 중 오류가 발생했습니다.", e);
        }

        try {
            sqsClient.sendMessage(SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(body)
                    .build());
        } catch (RuntimeException e) {
            throw new AnalysisQueueException("SQS에 분석 작업을 발행하는 중 오류가 발생했습니다: " + e.getMessage(), e);
        }
    }
}
