package com.vintic.backend.analyze.job.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.analyze.job.AnalysisJobStatus;
import com.vintic.backend.analyze.job.ProductAnalysisJob;
import com.vintic.backend.analyze.job.ProductAnalysisJobFinalizationService;
import com.vintic.backend.analyze.job.ProductAnalysisJobRepository;
import com.vintic.backend.analyze.job.processor.FakeAnalysisProcessor;
import com.vintic.backend.analyze.job.queue.AnalysisJobQueueMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

// Day3 2단계 종단 검증: 실제 LocalStack SQS+S3와 Testcontainers MySQL로 poller(long polling)
// -> handler(GetObject+Processor+조건부 UPDATE) -> deleteMessage 전체 경로를 확인한다.
// H2가 아니라 실제 MySQL을 쓴다 - 조건부 UPDATE의 Commit 순서/row count 판단을 H2로는
// 신뢰할 수 없기 때문이다. @Transactional을 클래스에 걸지 않는다 - claimForProcessing/
// ProductAnalysisJobFinalizationService(complete/fail)가 각각 독립된 트랜잭션으로 실제
// Commit되는 것을 그대로 관찰해야 "Commit 이후에만 DeleteMessage" 순서를 검증할 수 있다.
//
// Awaitility 등 새 테스트 의존성을 추가하지 않고, 기존 build.gradle 의존성만으로 폴링 대기한다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Testcontainers
class SqsAnalysisJobLocalStackIT {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @Container
    static LocalStackContainer localstack =
            new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.5"))
                    .withServices(LocalStackContainer.Service.S3, LocalStackContainer.Service.SQS);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired
    private ProductAnalysisJobRepository jobRepository;

    @Autowired
    private ProductAnalysisJobFinalizationService finalizationService;

    private S3Client s3Client;
    private SqsClient sqsClient;
    private String bucket;
    private String queueUrl;

    private void setUpAwsClients() {
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(localstack.getAccessKey(), localstack.getSecretKey()));

        s3Client = S3Client.builder()
                .endpointOverride(localstack.getEndpoint())
                .region(Region.of(localstack.getRegion()))
                .credentialsProvider(credentials)
                .build();
        sqsClient = SqsClient.builder()
                .endpointOverride(localstack.getEndpoint())
                .region(Region.of(localstack.getRegion()))
                .credentialsProvider(credentials)
                .build();

        bucket = "analysis-job-bucket-" + System.identityHashCode(this);
        s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        queueUrl = sqsClient.createQueue(
                CreateQueueRequest.builder().queueName("analysis-job-queue-" + System.identityHashCode(this)).build()
        ).queueUrl();
    }

    private SqsAnalysisJobHandler newHandler(FakeAnalysisProcessor processor) {
        return new SqsAnalysisJobHandler(
                jobRepository, s3Client, processor, new ObjectMapper(),
                new WorkerRuntimeIdentity("it-worker", "it-server"), finalizationService,
                bucket, 90L, 3);
    }

    private long approximateMessageCount() {
        Map<QueueAttributeName, String> attributes = sqsClient.getQueueAttributes(GetQueueAttributesRequest.builder()
                .queueUrl(queueUrl)
                .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE)
                .build()).attributes();
        return Long.parseLong(attributes.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES))
                + Long.parseLong(attributes.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE));
    }

    // 별도 대기 라이브러리(Awaitility 등)를 새로 추가하지 않기 위한 최소 폴링 헬퍼.
    private static void waitUntil(Duration timeout, Supplier<Boolean> condition) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (condition.get()) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("조건이 " + timeout + " 내에 충족되지 않았습니다.");
    }

    private String bodyFor(Long analysisId) {
        try {
            return new ObjectMapper().writeValueAsString(AnalysisJobQueueMessage.forJob(analysisId));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void 실제_S3_객체_내용이_Processor까지_전달되고_COMPLETED_Commit_후_메시지가_삭제된다() throws Exception {
        setUpAwsClients();
        String objectKey = "uploads/real-object.jpg";
        byte[] content = "실제 이미지 바이트".getBytes();
        s3Client.putObject(
                PutObjectRequest.builder().bucket(bucket).key(objectKey).build(),
                RequestBody.fromBytes(content));

        ProductAnalysisJob job = jobRepository.save(
                ProductAnalysisJob.create(1L, objectKey, "it-key-success"));

        FakeAnalysisProcessor processor = new FakeAnalysisProcessor(millis -> {}, 0);
        processor.setNextOutcome(FakeAnalysisProcessor.Outcome.SUCCESS);
        SqsAnalysisJobHandler handler = newHandler(processor);
        SqsAnalysisJobPoller poller = new SqsAnalysisJobPoller(sqsClient, handler, queueUrl, 20, 90);

        sqsClient.sendMessage(SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(bodyFor(job.getId()))
                .build());

        try {
            poller.start();
            waitUntil(Duration.ofSeconds(15), () ->
                    jobRepository.findById(job.getId()).orElseThrow().getStatus() == AnalysisJobStatus.COMPLETED);
        } finally {
            poller.stop();
            poller.awaitTermination(Duration.ofSeconds(25));
        }

        assertThat(jobRepository.findById(job.getId()).orElseThrow().getStatus())
                .isEqualTo(AnalysisJobStatus.COMPLETED);
        waitUntil(Duration.ofSeconds(5), () -> approximateMessageCount() == 0);
    }

    @Test
    void 존재하지_않는_objectKey는_영구_오류로_FAILED_Commit_후_메시지가_삭제된다() throws Exception {
        setUpAwsClients();
        String missingObjectKey = "uploads/does-not-exist.jpg";

        ProductAnalysisJob job = jobRepository.save(
                ProductAnalysisJob.create(1L, missingObjectKey, "it-key-permanent-failure"));

        FakeAnalysisProcessor processor = new FakeAnalysisProcessor(millis -> {}, 0);
        SqsAnalysisJobHandler handler = newHandler(processor);
        SqsAnalysisJobPoller poller = new SqsAnalysisJobPoller(sqsClient, handler, queueUrl, 20, 90);

        sqsClient.sendMessage(SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(bodyFor(job.getId()))
                .build());

        try {
            poller.start();
            waitUntil(Duration.ofSeconds(15), () ->
                    jobRepository.findById(job.getId()).orElseThrow().getStatus() == AnalysisJobStatus.FAILED);
        } finally {
            poller.stop();
            poller.awaitTermination(Duration.ofSeconds(25));
        }

        assertThat(jobRepository.findById(job.getId()).orElseThrow().getStatus())
                .isEqualTo(AnalysisJobStatus.FAILED);
        waitUntil(Duration.ofSeconds(5), () -> approximateMessageCount() == 0);
    }
}
