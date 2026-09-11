package com.vintic.backend.analyze.job.worker;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.services.sqs.SqsClient;

import static org.assertj.core.api.Assertions.assertThat;

// SqsWorkerClientConfig가 experiment-worker 프로필 + analysis.job.queue.type=sqs일 때만
// SqsClient 빈을 등록하는지 확인한다(1단계 RedisStreamConsumerConfigProfileTest와 동일한 방식).
class SqsWorkerClientConfigProfileTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(SqsWorkerClientConfig.class)
            .withPropertyValues(
                    "analysis.job.queue.sqs.region=ap-northeast-2",
                    "analysis.job.queue.sqs.endpoint-override=");

    @Test
    void experiment_worker와_sqs_타입이면_SqsClient_빈이_등록된다() {
        contextRunner
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("experiment-worker"))
                .withPropertyValues("analysis.job.queue.type=sqs")
                .run(context -> assertThat(context).hasSingleBean(SqsClient.class));
    }

    @Test
    void 프로필이_없으면_등록되지_않는다() {
        contextRunner
                .withPropertyValues("analysis.job.queue.type=sqs")
                .run(context -> assertThat(context).doesNotHaveBean(SqsClient.class));
    }

    @Test
    void experiment_worker이어도_type이_sqs가_아니면_등록되지_않는다() {
        contextRunner
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("experiment-worker"))
                .withPropertyValues("analysis.job.queue.type=in-memory")
                .run(context -> assertThat(context).doesNotHaveBean(SqsClient.class));
    }

    @Test
    void experiment_api_프로필이면_등록되지_않는다() {
        contextRunner
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("experiment-api"))
                .withPropertyValues("analysis.job.queue.type=sqs")
                .run(context -> assertThat(context).doesNotHaveBean(SqsClient.class));
    }
}
