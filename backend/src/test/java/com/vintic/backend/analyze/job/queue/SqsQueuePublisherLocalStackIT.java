package com.vintic.backend.analyze.job.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// SqsQueuePublisher가 (LocalStack) SQS에 실제로 SendMessage 하는지, 메시지 본문이 eventVersion과
// analysisId만 담고 objectKey 등 작업 정보는 담지 않는지 확인한다. SqsQueuePublisher는 자격증명을
// 코드에 담지 않고 DefaultCredentialsProvider를 쓰므로, 이 테스트에서는 AWS SDK가 인식하는
// system property(aws.accessKeyId/aws.secretAccessKey)로 더미 값만 채워준다 - 프로덕션 코드에
// 자격증명을 하드코딩하는 것과는 다르다.
@Testcontainers
class SqsQueuePublisherLocalStackIT {

    @Container
    static LocalStackContainer localstack =
            new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.5"))
                    .withServices(LocalStackContainer.Service.SQS);

    @BeforeAll
    static void setDummyAwsCredentials() {
        System.setProperty("aws.accessKeyId", "test");
        System.setProperty("aws.secretAccessKey", "test");
    }

    @Test
    void publish하면_SQS에_적재되고_본문에_objectKey가_없다() {
        SqsClient rawClient = SqsClient.builder()
                .endpointOverride(localstack.getEndpoint())
                .region(Region.of(localstack.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(localstack.getAccessKey(), localstack.getSecretKey())))
                .build();
        String queueUrl = rawClient.createQueue(
                CreateQueueRequest.builder().queueName("analysis-job-queue").build()
        ).queueUrl();

        SqsQueuePublisher publisher = new SqsQueuePublisher(
                queueUrl,
                localstack.getRegion(),
                localstack.getEndpoint().toString(),
                new ObjectMapper()
        );

        publisher.publish(AnalysisJobQueueMessage.forJob(123L));

        List<Message> messages = rawClient.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .waitTimeSeconds(5)
                .maxNumberOfMessages(1)
                .build()).messages();

        assertThat(messages).hasSize(1);
        String body = messages.get(0).body();
        assertThat(body).contains("\"eventVersion\":1");
        assertThat(body).contains("\"analysisId\":123");
        assertThat(body).doesNotContain("objectKey");
    }
}
