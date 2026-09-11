package com.vintic.backend.analyze.job.worker;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsClientBuilder;

import java.net.URI;

// SqsAnalysisJobPoller가 쓰는 SqsClient를 Spring이 관리하는 빈으로 등록한다 - Poller가
// 직접 생성하지 않는다. SqsClient는 SdkAutoCloseable을 구현하므로 Spring이 컨텍스트 종료 시
// close()를 destroy method로 자동 추론해 호출한다(별도 코드 불필요).
//
// SqsQueuePublisher와 동일한 region/endpoint-override 설정 키를 재사용한다(그 파일은 건드리지
// 않는다). experiment-worker 프로필 + analysis.job.queue.type=sqs일 때만 등록한다.
@Configuration
@Profile("experiment-worker")
@ConditionalOnProperty(prefix = "analysis.job.queue", name = "type", havingValue = "sqs")
public class SqsWorkerClientConfig {

    @Bean
    public SqsClient sqsClient(
            @Value("${analysis.job.queue.sqs.region}") String region,
            @Value("${analysis.job.queue.sqs.endpoint-override:}") String endpointOverride
    ) {
        SqsClientBuilder builder = SqsClient.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create());
        if (endpointOverride != null && !endpointOverride.isBlank()) {
            builder.endpointOverride(URI.create(endpointOverride));
        }
        return builder.build();
    }
}
