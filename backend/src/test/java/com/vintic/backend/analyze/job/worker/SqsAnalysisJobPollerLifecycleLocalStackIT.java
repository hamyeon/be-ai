package com.vintic.backend.analyze.job.worker;

import com.vintic.backend.BackendApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;

import static org.assertj.core.api.Assertions.assertThat;

// Day5 2단계: experiment-worker가 analysis.job.queue.type=sqs로 "실제로" 기동했을 때
// - 메시지가 없어도 poller 스레드가 long polling 상태로 계속 살아있는지(=프로세스가 즉시
//   종료되지 않는지)
// - Context 종료(SIGTERM과 동일한 경로 - ApplicationContext.close()가 @PreDestroy를 호출)
//   시 SqsAnalysisJobPollerLifecycle(3단계 shutdown coordinator)을 통해 poller 스레드가
//   실제로 멈추는지
// 를 실제 LocalStack SQS로 검증한다. ExperimentProfileContextTest(H2, type=in-memory 유사
// 설정)와 달리 이 테스트만 SQS 경로를 실제로 태운다 - 나머지(웹 계층/기존 Redis Consumer
// on-off)는 이미 그 테스트가 검증하므로 여기서 반복하지 않는다.
@Testcontainers
class SqsAnalysisJobPollerLifecycleLocalStackIT {

    private static final String POLLER_THREAD_NAME = "sqs-analysis-job-poller";

    @Container
    static LocalStackContainer localstack =
            new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.5"))
                    .withServices(LocalStackContainer.Service.SQS);

    @Test
    void 메시지가_없어도_poller_스레드가_10초_이상_long_polling_상태로_살아있고_context_종료시_정상_종료된다() throws Exception {
        String queueUrl = createQueue();

        ConfigurableApplicationContext context = new SpringApplicationBuilder(BackendApplication.class)
                .profiles("experiment-worker")
                .run(
                        "--spring.flyway.enabled=false", "--spring.jpa.hibernate.ddl-auto=update",
                        "--analysis.job.queue.type=sqs",
                        "--analysis.job.queue.sqs.queue-url=" + queueUrl,
                        "--analysis.job.queue.sqs.region=" + localstack.getRegion(),
                        "--analysis.job.queue.sqs.endpoint-override=" + localstack.getEndpoint(),
                        // 기본 20초 long polling 대기 대신 짧게 돌려 테스트 시간을 줄인다 -
                        // stale/visibility 관계 자체는 바꾸지 않는다(테스트 전용 오버라이드).
                        "--analysis.worker.poll.wait-time-seconds=3"
                );

        try {
            assertThat(context.getBeansOfType(WorkerQueueTypeGuard.class)).hasSize(1);
            assertThat(context.getBeansOfType(SqsAnalysisJobPollerLifecycle.class)).hasSize(1);

            Thread.sleep(10_000);
            assertThat(findPollerThread())
                    .as("poller 스레드가 10초 뒤에도 살아있어야 한다(메시지 없이도 즉시 종료되지 않음)")
                    .isPresent();
            assertThat(findPollerThread().orElseThrow().isAlive()).isTrue();
        } finally {
            context.close();
        }

        // Context.close() -> @PreDestroy(SqsAnalysisJobPollerLifecycle.stopPolling())가
        // poller.stop() + awaitTermination()을 이미 동기적으로 호출했으므로, close()가
        // 반환된 시점에는 poller 스레드가 이미 죽어 있어야 한다.
        assertThat(findPollerThread())
                .as("context 종료 후 poller 스레드가 남아있으면 안 된다")
                .isEmpty();
    }

    private java.util.Optional<Thread> findPollerThread() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> POLLER_THREAD_NAME.equals(t.getName()))
                .findFirst();
    }

    private String createQueue() {
        SqsClient sqsClient = SqsClient.builder()
                .endpointOverride(localstack.getEndpoint())
                .region(Region.of(localstack.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(localstack.getAccessKey(), localstack.getSecretKey())))
                .build();
        try {
            return sqsClient.createQueue(CreateQueueRequest.builder()
                    .queueName("worker-lifecycle-it-queue")
                    .build()).queueUrl();
        } finally {
            sqsClient.close();
        }
    }
}
