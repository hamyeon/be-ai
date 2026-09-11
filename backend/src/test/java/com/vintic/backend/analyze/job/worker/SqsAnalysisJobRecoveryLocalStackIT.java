package com.vintic.backend.analyze.job.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.analyze.job.AnalysisJobStatus;
import com.vintic.backend.analyze.job.AnalysisResult;
import com.vintic.backend.analyze.job.AnalysisResultRepository;
import com.vintic.backend.analyze.job.ProductAnalysisJob;
import com.vintic.backend.analyze.job.ProductAnalysisJobFinalizationService;
import com.vintic.backend.analyze.job.ProductAnalysisJobRepository;
import com.vintic.backend.analyze.job.processor.FakeAnalysisProcessor;
import com.vintic.backend.analyze.job.queue.AnalysisJobQueueMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
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
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

// Day4 3단계: LocalStack SQS(+DLQ/Redrive)·S3와 Testcontainers MySQL로 실제 crash/중복/재시도
// 복구 흐름을 검증한다. 2단계에서 이미 mock으로 검증한 상태별 DELETE/RETAIN, fencing, UNIQUE
// rollback은 여기서 반복하지 않는다 - 이 클래스는 "여러 컴포넌트가 실제로 맞물려 복구가
// 일어나는지"만 본다. 시나리오별 상세 설명은 각 @Test 메서드 주석 참고.
//
// stale=1초 < visibility=2초 관계를 유지한다(docs/infra-sprint/contracts.md 참고) - Day4 2단계
// 후속 지시로 바로잡은 "stale < visibility" 관계가 테스트 값에도 그대로 적용돼야 재노출 시점에
// stale 재선점이 실제로 가능하다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Testcontainers
class SqsAnalysisJobRecoveryLocalStackIT {

    private static final int STALE_AFTER_SECONDS = 1;
    private static final long STALE_AFTER_MICROS = STALE_AFTER_SECONDS * 1_000_000L;
    private static final int VISIBILITY_TIMEOUT_SECONDS = 2;
    private static final int WAIT_TIME_SECONDS = 1;
    private static final int MAX_RECEIVE_COUNT = 3;

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
    private AnalysisResultRepository resultRepository;

    @Autowired
    private ProductAnalysisJobFinalizationService finalizationService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private S3Client s3Client;
    private SqsClient sqsClient;
    private String bucket;
    private String queueUrl;
    private String dlqUrl;

    // ---- 공통 fixture ----

    private void setUpAwsClientsWithDlq() {
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

        String suffix = System.identityHashCode(this) + "-" + UUID.randomUUID().toString().substring(0, 8);
        bucket = "analysis-recovery-bucket-" + suffix;
        s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());

        dlqUrl = sqsClient.createQueue(CreateQueueRequest.builder()
                .queueName("analysis-recovery-dlq-" + suffix)
                .build()).queueUrl();
        String dlqArn = queueArn(dlqUrl);

        String redrivePolicyJson = "{\"deadLetterTargetArn\":\"" + dlqArn + "\",\"maxReceiveCount\":" + MAX_RECEIVE_COUNT + "}";
        Map<QueueAttributeName, String> mainAttributes = new HashMap<>();
        mainAttributes.put(QueueAttributeName.VISIBILITY_TIMEOUT, String.valueOf(VISIBILITY_TIMEOUT_SECONDS));
        mainAttributes.put(QueueAttributeName.REDRIVE_POLICY, redrivePolicyJson);

        queueUrl = sqsClient.createQueue(CreateQueueRequest.builder()
                .queueName("analysis-recovery-queue-" + suffix)
                .attributes(mainAttributes)
                .build()).queueUrl();
    }

    private String queueArn(String url) {
        return sqsClient.getQueueAttributes(GetQueueAttributesRequest.builder()
                .queueUrl(url)
                .attributeNames(QueueAttributeName.QUEUE_ARN)
                .build()).attributes().get(QueueAttributeName.QUEUE_ARN);
    }

    private SqsAnalysisJobHandler newHandler(FakeAnalysisProcessor processor, String workerId) {
        return new SqsAnalysisJobHandler(
                jobRepository, s3Client, processor, objectMapper,
                new WorkerRuntimeIdentity(workerId, "server-" + workerId), finalizationService,
                bucket, STALE_AFTER_SECONDS, MAX_RECEIVE_COUNT);
    }

    private String bodyFor(Long analysisId) {
        try {
            return objectMapper.writeValueAsString(AnalysisJobQueueMessage.forJob(analysisId));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private void putObject(String objectKey, byte[] content) {
        s3Client.putObject(PutObjectRequest.builder().bucket(bucket).key(objectKey).build(),
                RequestBody.fromBytes(content));
    }

    private ProductAnalysisJob newJob(String objectKey, String idempotencyKey) {
        return jobRepository.save(ProductAnalysisJob.create(1L, objectKey, idempotencyKey));
    }

    // 단일 receiveMessage 호출. maxNumberOfMessages=1, ApproximateReceiveCount 속성을 요청한다 -
    // 프로덕션 Poller와 동일한 요청 파라미터를 테스트 코드가 직접 재현한다.
    private Message receiveOne(String url) {
        List<Message> messages = sqsClient.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(url)
                .maxNumberOfMessages(1)
                .waitTimeSeconds(WAIT_TIME_SECONDS)
                .visibilityTimeout(VISIBILITY_TIMEOUT_SECONDS)
                .attributeNamesWithStrings("ApproximateReceiveCount")
                .build()).messages();
        return messages.isEmpty() ? null : messages.get(0);
    }

    // 메시지가 아직 없을 수 있는 상황(재노출/DLQ 이동 대기)에서 bounded polling으로 1건을 기다린다.
    private Message waitForOneMessage(String url, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            Message message = receiveOne(url);
            if (message != null) {
                return message;
            }
        }
        return null;
    }

    private static void waitUntil(Duration timeout, Supplier<Boolean> condition) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (condition.get()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("조건이 " + timeout + " 내에 충족되지 않았습니다.");
    }

    private Integer parseReceiveCount(Message message) {
        String raw = message.attributesAsStrings().get("ApproximateReceiveCount");
        return raw == null ? null : Integer.parseInt(raw);
    }

    private ReceivedQueueMessage toReceivedMessage(Message message) {
        return new ReceivedQueueMessage(message.messageId(), message.body(), parseReceiveCount(message));
    }

    private void deleteMessage(String url, Message message) {
        sqsClient.deleteMessage(DeleteMessageRequest.builder()
                .queueUrl(url)
                .receiptHandle(message.receiptHandle())
                .build());
    }

    private List<AnalysisResult> resultsFor(Long analysisId) {
        return resultRepository.findAll().stream()
                .filter(r -> r.getAnalysisId().equals(analysisId))
                .toList();
    }

    private long approximateMessageCount(String url) {
        Map<QueueAttributeName, String> attributes = sqsClient.getQueueAttributes(GetQueueAttributesRequest.builder()
                .queueUrl(url)
                .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE)
                .build()).attributes();
        return Long.parseLong(attributes.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES))
                + Long.parseLong(attributes.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE));
    }

    // ---- 필수 시나리오 1: 동일 analysisId 메시지 2개 ----

    @Test
    void 동일_analysisId_중복_메시지는_한_Worker만_claim에_성공하고_결과는_1건이다() throws Exception {
        setUpAwsClientsWithDlq();
        String objectKey = "uploads/duplicate.jpg";
        putObject(objectKey, "duplicate-bytes".getBytes());
        ProductAnalysisJob job = newJob(objectKey, "it-key-duplicate");

        CountDownLatch aEnteredProcessor = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        FakeAnalysisProcessor processorA = new FakeAnalysisProcessor(millis -> {
            aEnteredProcessor.countDown();
            try {
                assertThat(releaseA.await(15, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, 0);
        processorA.setNextOutcome(FakeAnalysisProcessor.Outcome.SUCCESS);
        SqsAnalysisJobHandler handlerA = newHandler(processorA, "worker-A");

        sqsClient.sendMessage(SendMessageRequest.builder().queueUrl(queueUrl).messageBody(bodyFor(job.getId())).build());
        sqsClient.sendMessage(SendMessageRequest.builder().queueUrl(queueUrl).messageBody(bodyFor(job.getId())).build());

        ExecutorService pool = Executors.newFixedThreadPool(1);
        try {
            Future<Object[]> futureA = pool.submit(() -> {
                Message m = receiveOne(queueUrl);
                SqsAnalysisJobHandler.Outcome outcome = handlerA.handle(toReceivedMessage(m));
                return new Object[]{m, outcome};
            });

            assertThat(aEnteredProcessor.await(10, TimeUnit.SECONDS))
                    .as("A가 claim 이후 Processor 안에서 실제로 블로킹돼야 경쟁 구간이 보장된다")
                    .isTrue();
            waitUntil(Duration.ofSeconds(5), () -> {
                ProductAnalysisJob reloaded = jobRepository.findById(job.getId()).orElseThrow();
                return reloaded.getStatus() == AnalysisJobStatus.PROCESSING && "worker-A".equals(reloaded.getWorkerId());
            });

            // B가 두 번째(중복) 메시지를 받아 같은 analysisId를 claim 시도 - A가 아직 완료 전(fresh
            // PROCESSING)이므로 재선점에 실패해야 한다.
            Message secondMessage = receiveOne(queueUrl);
            assertThat(secondMessage).isNotNull();
            FakeAnalysisProcessor processorB = new FakeAnalysisProcessor(millis -> {
                throw new AssertionError("B는 claim에 실패해야 하므로 Processor가 호출되면 안 된다");
            }, 0);
            SqsAnalysisJobHandler handlerB = newHandler(processorB, "worker-B");
            SqsAnalysisJobHandler.Outcome outcomeB = handlerB.handle(toReceivedMessage(secondMessage));
            assertThat(outcomeB).isEqualTo(SqsAnalysisJobHandler.Outcome.RETAIN);
            assertThat(jobRepository.findById(job.getId()).orElseThrow().getWorkerId()).isEqualTo("worker-A");

            releaseA.countDown();
            Object[] resultA = futureA.get(15, TimeUnit.SECONDS);
            Message firstMessage = (Message) resultA[0];
            SqsAnalysisJobHandler.Outcome outcomeA = (SqsAnalysisJobHandler.Outcome) resultA[1];
            assertThat(outcomeA).isEqualTo(SqsAnalysisJobHandler.Outcome.DELETE);
            deleteMessage(queueUrl, firstMessage);

            ProductAnalysisJob finalJob = jobRepository.findById(job.getId()).orElseThrow();
            assertThat(finalJob.getStatus()).isEqualTo(AnalysisJobStatus.COMPLETED);
            assertThat(finalJob.getWorkerId()).isEqualTo("worker-A");
            List<AnalysisResult> results = resultsFor(job.getId());
            assertThat(results).hasSize(1);
            assertThat(results.get(0).getRawResult()).isEqualTo("fake-result-" + job.getId());

            // 남은 중복(B가 받았던 두 번째 delivery)은 지우지 않았으므로 Visibility 경과 후
            // 다시 노출된다 - 이제는 COMPLETED(terminal)이므로 재수신 시 DELETE로 정리 가능해야 한다.
            Message redeliveredDuplicate = waitForOneMessage(queueUrl, Duration.ofSeconds(10));
            assertThat(redeliveredDuplicate).isNotNull();
            SqsAnalysisJobHandler.Outcome dupOutcome = handlerB.handle(toReceivedMessage(redeliveredDuplicate));
            assertThat(dupOutcome).isEqualTo(SqsAnalysisJobHandler.Outcome.DELETE);
            deleteMessage(queueUrl, redeliveredDuplicate);

            waitUntil(Duration.ofSeconds(10), () -> approximateMessageCount(queueUrl) == 0);
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- 필수 시나리오 2 (a): maxReceiveCount 이전 crash - deterministic crash-window ----
    // 실제 프로세스 종료가 아니라, A를 Processor 안에서 영구히 블로킹시켜 "결과 저장 이전에 멈춘
    // Worker"를 결정적으로 재현한다. 실제 kill 검증은 아래 (b) 테스트가 별도로 담당한다.

    @Test
    void crash_deterministic_A가_결과_저장_전에_멈추면_B가_stale_재선점하여_완료한다() throws Exception {
        setUpAwsClientsWithDlq();
        String objectKey = "uploads/crash-deterministic.jpg";
        putObject(objectKey, "crash-bytes".getBytes());
        ProductAnalysisJob job = newJob(objectKey, "it-key-crash-deterministic");

        CountDownLatch aEnteredProcessor = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        FakeAnalysisProcessor processorA = new FakeAnalysisProcessor(millis -> {
            aEnteredProcessor.countDown();
            try {
                releaseA.await(); // 이 테스트의 finally에서만 풀린다 - "죽어서 다시 돌아오지 않는 Worker"를 흉내낸다.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, 0);
        processorA.setNextOutcome(FakeAnalysisProcessor.Outcome.SUCCESS);
        SqsAnalysisJobHandler handlerA = newHandler(processorA, "worker-A-crash");

        sqsClient.sendMessage(SendMessageRequest.builder().queueUrl(queueUrl).messageBody(bodyFor(job.getId())).build());

        ExecutorService pool = Executors.newFixedThreadPool(1);
        try {
            pool.submit(() -> {
                Message m = receiveOne(queueUrl);
                handlerA.handle(toReceivedMessage(m));
                return null;
            });

            assertThat(aEnteredProcessor.await(10, TimeUnit.SECONDS)).isTrue();
            waitUntil(Duration.ofSeconds(5), () -> {
                ProductAnalysisJob reloaded = jobRepository.findById(job.getId()).orElseThrow();
                return reloaded.getStatus() == AnalysisJobStatus.PROCESSING && "worker-A-crash".equals(reloaded.getWorkerId());
            });

            Message redelivered = waitForOneMessage(queueUrl,
                    Duration.ofSeconds(VISIBILITY_TIMEOUT_SECONDS + STALE_AFTER_SECONDS + 8L));
            assertThat(redelivered).isNotNull();
            assertThat(parseReceiveCount(redelivered)).isGreaterThanOrEqualTo(2);

            FakeAnalysisProcessor processorB = new FakeAnalysisProcessor(millis -> {}, 0);
            processorB.setNextOutcome(FakeAnalysisProcessor.Outcome.SUCCESS);
            SqsAnalysisJobHandler handlerB = newHandler(processorB, "worker-B-crash");
            SqsAnalysisJobHandler.Outcome outcomeB = handlerB.handle(toReceivedMessage(redelivered));
            assertThat(outcomeB).isEqualTo(SqsAnalysisJobHandler.Outcome.DELETE);
            deleteMessage(queueUrl, redelivered);

            ProductAnalysisJob finalJob = jobRepository.findById(job.getId()).orElseThrow();
            assertThat(finalJob.getStatus()).isEqualTo(AnalysisJobStatus.COMPLETED);
            assertThat(finalJob.getWorkerId()).isEqualTo("worker-B-crash");
            assertThat(resultsFor(job.getId())).hasSize(1);
        } finally {
            releaseA.countDown();
            pool.shutdownNow();
        }
    }

    // ---- 필수 시나리오 2 (b): maxReceiveCount 이전 crash - 실제 프로세스 강제 종료 ----
    // ClaimAndCrashMain을 별도 child JVM으로 실행해 실제 claim을 Commit시킨 뒤
    // Process.destroyForcibly()로 강제 종료한다. Spring/S3/SQS를 부팅하지 않는 최소 harness다 -
    // 이 시나리오가 검증해야 하는 지점(DB claim Commit 이후 죽음)에는 그것으로 충분하다.

    @Test
    void crash_실제_프로세스_강제종료_후_B가_stale_재선점하여_완료한다() throws Exception {
        setUpAwsClientsWithDlq();
        String objectKey = "uploads/crash-real-kill.jpg";
        putObject(objectKey, "crash-real-bytes".getBytes());
        ProductAnalysisJob job = newJob(objectKey, "it-key-crash-real-kill");

        sqsClient.sendMessage(SendMessageRequest.builder().queueUrl(queueUrl).messageBody(bodyFor(job.getId())).build());

        // 부모가 먼저 receive해 visibility 타이머를 실제로 시작시킨다 - 이 delivery를 삭제하지
        // 않으므로(child가 죽으면 아무도 지우지 않는다) Visibility Timeout 이후 다시 노출된다.
        Message firstDelivery = receiveOne(queueUrl);
        assertThat(firstDelivery).isNotNull();

        Process child = launchClaimAndCrash(job.getId(), "worker-A-realkill");
        try {
            String firstLine = readFirstLine(child, Duration.ofSeconds(15));
            assertThat(firstLine).as("child 프로세스가 claim 결과를 stdout으로 보고해야 한다")
                    .isEqualTo("CLAIMED:1");

            ProductAnalysisJob claimed = jobRepository.findById(job.getId()).orElseThrow();
            assertThat(claimed.getStatus()).isEqualTo(AnalysisJobStatus.PROCESSING);
            assertThat(claimed.getWorkerId()).isEqualTo("worker-A-realkill");
            assertThat(child.isAlive()).as("강제 종료 전에는 child 프로세스가 실제로 살아있어야 한다").isTrue();

            child.destroyForcibly();
            boolean exitedWithinTimeout = child.waitFor(15, TimeUnit.SECONDS);
            assertThat(exitedWithinTimeout).as("child 프로세스가 강제 종료 후 실제로 종료돼야 한다").isTrue();
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
                child.waitFor(15, TimeUnit.SECONDS);
            }
        }

        assertThat(child.isAlive()).as("kill 이후 child 프로세스가 실제로 사라져야 한다").isFalse();

        Message redelivered = waitForOneMessage(queueUrl,
                Duration.ofSeconds(VISIBILITY_TIMEOUT_SECONDS + STALE_AFTER_SECONDS + 8L));
        assertThat(redelivered).isNotNull();

        FakeAnalysisProcessor processorB = new FakeAnalysisProcessor(millis -> {}, 0);
        processorB.setNextOutcome(FakeAnalysisProcessor.Outcome.SUCCESS);
        SqsAnalysisJobHandler handlerB = newHandler(processorB, "worker-B-realkill");
        SqsAnalysisJobHandler.Outcome outcomeB = handlerB.handle(toReceivedMessage(redelivered));
        assertThat(outcomeB).isEqualTo(SqsAnalysisJobHandler.Outcome.DELETE);
        deleteMessage(queueUrl, redelivered);

        ProductAnalysisJob finalJob = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(finalJob.getStatus()).isEqualTo(AnalysisJobStatus.COMPLETED);
        assertThat(finalJob.getWorkerId()).isEqualTo("worker-B-realkill");
        assertThat(resultsFor(job.getId())).hasSize(1);
    }

    private Process launchClaimAndCrash(Long analysisId, String workerId) throws IOException {
        String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        String classpath = System.getProperty("java.class.path");
        // Windows는 CreateProcess에 넘기는 전체 명령줄 길이에 제한이 있다 - Gradle 테스트
        // classpath는 의존성 jar 전체 경로를 나열해 그 한도를 쉽게 넘는다(실제로 error=206
        // "파일 이름이나 확장명이 너무 깁니다"로 재현됨). classpath를 argfile로 빼서
        // `java @file` 형태로 넘기면 OS 명령줄 길이 제한을 우회할 수 있다(JDK 9+ 표준 기능).
        Path classpathArgFile = Files.createTempFile("claim-and-crash-classpath", ".args");
        classpathArgFile.toFile().deleteOnExit();
        Files.writeString(classpathArgFile, "-cp \"" + classpath + "\"" + System.lineSeparator());

        ProcessBuilder builder = new ProcessBuilder(
                javaBin, "@" + classpathArgFile.toAbsolutePath(), ClaimAndCrashMain.class.getName(),
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword(),
                String.valueOf(analysisId), workerId, String.valueOf(STALE_AFTER_MICROS));
        builder.redirectErrorStream(true);
        return builder.start();
    }

    private String readFirstLine(Process process, Duration timeout) throws Exception {
        LinkedBlockingQueue<String> lines = new LinkedBlockingQueue<>();
        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    lines.offer(line);
                }
            } catch (IOException ignored) {
                // 프로세스가 강제 종료되면 스트림이 끊기는 것이 정상이다.
            }
        }, "child-stdout-reader");
        reader.setDaemon(true);
        reader.start();
        return lines.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    // ---- 필수 시나리오 3: 첫 AI 호출만 retryable 5xx ----

    @Test
    void 첫_호출만_retryable_5xx면_실제_재노출_후_stale_재선점으로_성공한다() throws Exception {
        setUpAwsClientsWithDlq();
        String objectKey = "uploads/first-call-5xx.jpg";
        putObject(objectKey, "first-call-bytes".getBytes());
        ProductAnalysisJob job = newJob(objectKey, "it-key-first-5xx");

        FakeAnalysisProcessor processor = new FakeAnalysisProcessor(millis -> {}, 0);
        processor.setNextOutcome(FakeAnalysisProcessor.Outcome.TRANSIENT_ERROR);
        SqsAnalysisJobHandler handler = newHandler(processor, "worker-retry");

        sqsClient.sendMessage(SendMessageRequest.builder().queueUrl(queueUrl).messageBody(bodyFor(job.getId())).build());

        Message first = receiveOne(queueUrl);
        assertThat(first).isNotNull();
        assertThat(parseReceiveCount(first)).isEqualTo(1);
        SqsAnalysisJobHandler.Outcome outcome1 = handler.handle(toReceivedMessage(first));
        assertThat(outcome1).isEqualTo(SqsAnalysisJobHandler.Outcome.RETAIN);
        // 첫 delivery는 지우지 않는다(Poller가 하듯 테스트 코드도 RETAIN이면 삭제하지 않는다).
        ProductAnalysisJob afterFirst = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(afterFirst.getStatus()).isEqualTo(AnalysisJobStatus.PROCESSING);

        // 다음 실제 재노출 전에 Processor를 성공으로 바꿔둔다.
        processor.setNextOutcome(FakeAnalysisProcessor.Outcome.SUCCESS);

        Message second = waitForOneMessage(queueUrl, Duration.ofSeconds(VISIBILITY_TIMEOUT_SECONDS + 8L));
        assertThat(second).as("Visibility Timeout 경과 후 실제로 재노출돼야 한다").isNotNull();
        assertThat(parseReceiveCount(second)).isEqualTo(2);

        ProductAnalysisJob beforeSecond = jobRepository.findById(job.getId()).orElseThrow();
        SqsAnalysisJobHandler.Outcome outcome2 = handler.handle(toReceivedMessage(second));
        assertThat(outcome2).isEqualTo(SqsAnalysisJobHandler.Outcome.DELETE);
        deleteMessage(queueUrl, second);

        ProductAnalysisJob afterSecond = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(afterSecond.getStatus()).isEqualTo(AnalysisJobStatus.COMPLETED);
        assertThat(afterSecond.getProcessingStartedAt())
                .as("stale 재선점으로 processingStartedAt이 갱신돼야 한다")
                .isNotEqualTo(afterFirst.getProcessingStartedAt());
        assertThat(resultsFor(job.getId())).hasSize(1);
    }

    // ---- 필수 시나리오 4: 계속 retryable 5xx -> 최종 FAILED + DLQ ----

    @Test
    void 계속_retryable_5xx면_최종_receive에서_FAILED_Commit_후_DLQ로_이동한다() throws Exception {
        setUpAwsClientsWithDlq();
        String objectKey = "uploads/always-5xx.jpg";
        putObject(objectKey, "always-5xx-bytes".getBytes());
        ProductAnalysisJob job = newJob(objectKey, "it-key-always-5xx");

        FakeAnalysisProcessor processor = new FakeAnalysisProcessor(millis -> {}, 0);
        processor.setNextOutcome(FakeAnalysisProcessor.Outcome.TRANSIENT_ERROR);
        SqsAnalysisJobHandler handler = newHandler(processor, "worker-exhaust");

        sqsClient.sendMessage(SendMessageRequest.builder().queueUrl(queueUrl).messageBody(bodyFor(job.getId())).build());

        for (int attempt = 1; attempt <= MAX_RECEIVE_COUNT; attempt++) {
            Message message = waitForOneMessage(queueUrl, Duration.ofSeconds(VISIBILITY_TIMEOUT_SECONDS + 8L));
            assertThat(message).as("attempt=" + attempt + "에서 실제 재노출을 받아야 한다").isNotNull();
            assertThat(parseReceiveCount(message)).isEqualTo(attempt);

            SqsAnalysisJobHandler.Outcome outcome = handler.handle(toReceivedMessage(message));
            assertThat(outcome).isEqualTo(SqsAnalysisJobHandler.Outcome.RETAIN);
            // Handler가 RETAIN을 반환했을 때만 삭제하지 않는 Poller 규칙을 테스트 코드도 그대로 따른다
            // - 여기서는 attempt와 무관하게 항상 RETAIN이므로 매번 deleteMessage를 호출하지 않는다.

            AnalysisJobStatus status = jobRepository.findById(job.getId()).orElseThrow().getStatus();
            if (attempt < MAX_RECEIVE_COUNT) {
                assertThat(status).as("attempt=" + attempt).isEqualTo(AnalysisJobStatus.PROCESSING);
            } else {
                assertThat(status).as("최종 허용 receive에서 fenced FAILED Commit이 반영돼야 한다")
                        .isEqualTo(AnalysisJobStatus.FAILED);
            }
        }

        // 최종 receive 이후에도 Worker는 삭제하지 않았으므로, 이 delivery의 Visibility Timeout이
        // 지나면 SQS Redrive Policy가 Main Queue가 아니라 DLQ로 이 메시지를 옮겨야 한다. LocalStack은
        // 이 이동을 순수 백그라운드 타이머가 아니라 Main Queue에 대한 다음 receive 시도 시점에
        // 평가하는 것으로 관찰돼, DLQ와 Main Queue를 교대로 bounded polling한다(Main Queue receive는
        // 이동 평가를 유도하는 목적일 뿐이며, receiveCount가 이미 소진됐으므로 그 응답 자체를
        // 재시도로 쓰지 않는다 - 이 delivery는 이미 FAILED Commit까지 끝난 뒤라 Handler를 다시
        // 부르지 않는다).
        Message dlqMessage = null;
        Instant dlqDeadline = Instant.now().plusSeconds(VISIBILITY_TIMEOUT_SECONDS + 25L);
        while (Instant.now().isBefore(dlqDeadline) && dlqMessage == null) {
            dlqMessage = receiveOne(dlqUrl);
            if (dlqMessage == null) {
                receiveOne(queueUrl); // 이동 평가를 유도만 한다 - 응답은 사용하지 않는다.
            }
        }
        assertThat(dlqMessage).as("LocalStack Redrive Policy로 DLQ에 메시지가 도착해야 한다").isNotNull();
        AnalysisJobQueueMessage dlqPayload = objectMapper.readValue(dlqMessage.body(), AnalysisJobQueueMessage.class);
        assertThat(dlqPayload.analysisId()).isEqualTo(job.getId());

        ProductAnalysisJob finalJob = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(finalJob.getStatus()).isEqualTo(AnalysisJobStatus.FAILED);
        assertThat(resultsFor(job.getId())).isEmpty();

        waitUntil(Duration.ofSeconds(10), () -> approximateMessageCount(queueUrl) == 0);
    }

    // ---- 필수 시나리오 5 + 6: A가 Visibility보다 오래 처리 -> B가 완료 -> A는 LEASE_LOST ->
    // 이후 같은 A Poller thread가 다음 메시지를 정상 처리 ----

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void Visibility보다_오래_처리하는_A는_LEASE_LOST되고_B가_완료하며_이후_A_Poller는_다음_메시지를_정상_처리한다(
            CapturedOutput output) throws Exception {
        setUpAwsClientsWithDlq();
        String objectKey = "uploads/visibility-exceed.jpg";
        putObject(objectKey, "visibility-exceed-bytes".getBytes());
        ProductAnalysisJob job = newJob(objectKey, "it-key-visibility-exceed");

        CountDownLatch aEnteredProcessor = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        FakeAnalysisProcessor processorA = new FakeAnalysisProcessor(millis -> {
            aEnteredProcessor.countDown();
            try {
                assertThat(releaseA.await(30, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, 0);
        processorA.setNextOutcome(FakeAnalysisProcessor.Outcome.SUCCESS);
        SqsAnalysisJobHandler handlerA = newHandler(processorA, "worker-A-visibility");
        SqsAnalysisJobPoller pollerA = new SqsAnalysisJobPoller(
                sqsClient, handlerA, queueUrl, WAIT_TIME_SECONDS, VISIBILITY_TIMEOUT_SECONDS);

        sqsClient.sendMessage(SendMessageRequest.builder().queueUrl(queueUrl).messageBody(bodyFor(job.getId())).build());

        try {
            pollerA.start();
            assertThat(aEnteredProcessor.await(10, TimeUnit.SECONDS)).isTrue();
            waitUntil(Duration.ofSeconds(5), () -> {
                ProductAnalysisJob reloaded = jobRepository.findById(job.getId()).orElseThrow();
                return reloaded.getStatus() == AnalysisJobStatus.PROCESSING && "worker-A-visibility".equals(reloaded.getWorkerId());
            });

            // A가 Processor 안에서 블로킹된 채로 Visibility Timeout + stale threshold를 실제로
            // 넘긴다 - A는 receiveMessage를 다시 부르지 않으므로(같은 handle() 호출 안에 갇혀
            // 있음) 이 메시지는 자연히 재노출된다.
            Message redelivered = waitForOneMessage(queueUrl,
                    Duration.ofSeconds(VISIBILITY_TIMEOUT_SECONDS + STALE_AFTER_SECONDS + 8L));
            assertThat(redelivered).isNotNull();

            FakeAnalysisProcessor processorB = new FakeAnalysisProcessor(millis -> {}, 0);
            processorB.setNextOutcome(FakeAnalysisProcessor.Outcome.SUCCESS);
            SqsAnalysisJobHandler handlerB = newHandler(processorB, "worker-B-visibility");
            SqsAnalysisJobHandler.Outcome outcomeB = handlerB.handle(toReceivedMessage(redelivered));
            assertThat(outcomeB).isEqualTo(SqsAnalysisJobHandler.Outcome.DELETE);
            deleteMessage(queueUrl, redelivered);

            ProductAnalysisJob afterB = jobRepository.findById(job.getId()).orElseThrow();
            assertThat(afterB.getStatus()).isEqualTo(AnalysisJobStatus.COMPLETED);
            assertThat(afterB.getWorkerId()).isEqualTo("worker-B-visibility");
            List<AnalysisResult> resultsAfterB = resultsFor(job.getId());
            assertThat(resultsAfterB).hasSize(1);
            assertThat(resultsAfterB.get(0).getRawResult()).isEqualTo("fake-result-" + job.getId());

            // A를 풀어준다 - A의 completeIfOwned는 이미 worker_id가 B로 바뀐 뒤라 affected=0이어야
            // 하고(LEASE_LOST), Handler는 RETAIN을 반환해야 한다. Poller 루프 안에서 소비되므로
            // 반환값을 직접 잡을 수는 없지만, ProductAnalysisJobFinalizationService.complete()가
            // LEASE_LOST 분기에서 analysisId/workerId를 함께 남기는 WARN 로그를 Spring Boot Test의
            // 출력 캡처(OutputCaptureExtension, 새 외부 라이브러리 아님)로 직접 확인한다 - 전체 로그
            // 문장이나 timestamp를 비교하지 않고, 같은 줄에 "lease를 상실했습니다" 문구와
            // analysisId=<이 job의 id>, workerId=worker-A-visibility가 함께 있는지만 본다.
            Thread pollerAThreadBeforeNext = (Thread) ReflectionTestUtils.getField(pollerA, "pollingThread");
            releaseA.countDown();

            waitUntil(Duration.ofSeconds(10), () -> output.getOut().lines().anyMatch(line ->
                    line.contains("lease를 상실했습니다")
                            && line.contains("analysisId=" + job.getId())
                            && line.contains("workerId=worker-A-visibility")));

            // A의 handle() 1건이 끝나고 poll loop로 되돌아간 뒤에도 결과가 여전히 B 것인지
            // (A의 늦은 시도가 덮어쓰지 못했는지) DB로도 확인한다.
            waitUntil(Duration.ofSeconds(5), () -> {
                ProductAnalysisJob reloaded = jobRepository.findById(job.getId()).orElseThrow();
                return reloaded.getStatus() == AnalysisJobStatus.COMPLETED
                        && "worker-B-visibility".equals(reloaded.getWorkerId());
            });
            assertThat(resultsFor(job.getId())).hasSize(1);

            // ---- 시나리오 6: 같은 A Poller thread가 다음(다른 analysisId) 메시지를 정상 처리 ----
            String nextObjectKey = "uploads/after-lease-lost.jpg";
            putObject(nextObjectKey, "after-lease-lost-bytes".getBytes());
            ProductAnalysisJob nextJob = newJob(nextObjectKey, "it-key-after-lease-lost");
            sqsClient.sendMessage(SendMessageRequest.builder().queueUrl(queueUrl).messageBody(bodyFor(nextJob.getId())).build());

            waitUntil(Duration.ofSeconds(15), () ->
                    jobRepository.findById(nextJob.getId()).orElseThrow().getStatus() == AnalysisJobStatus.COMPLETED);

            Thread pollerAThreadAfterNext = (Thread) ReflectionTestUtils.getField(pollerA, "pollingThread");
            assertThat(pollerAThreadAfterNext)
                    .as("새 Poller가 아니라 LEASE_LOST를 겪은 바로 그 A Poller 인스턴스/스레드여야 한다")
                    .isSameAs(pollerAThreadBeforeNext);
            assertThat(pollerAThreadAfterNext.isAlive()).isTrue();

            ProductAnalysisJob finalNextJob = jobRepository.findById(nextJob.getId()).orElseThrow();
            assertThat(finalNextJob.getWorkerId()).isEqualTo("worker-A-visibility");
            assertThat(resultsFor(nextJob.getId())).hasSize(1);
        } finally {
            pollerA.stop();
            pollerA.awaitTermination(Duration.ofSeconds(10));
        }
    }
}
