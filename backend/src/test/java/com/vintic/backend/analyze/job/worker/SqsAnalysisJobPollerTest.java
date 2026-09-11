package com.vintic.backend.analyze.job.worker;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// SqsAnalysisJobPoller의 poll loop가 receiveMessage 파라미터(waitTimeSeconds/visibilityTimeout/
// ApproximateReceiveCount 요청)를 올바르게 쓰는지, Handler의 DELETE/RETAIN 판정에 따라
// deleteMessage를 호출하는지, ApproximateReceiveCount를 ReceivedQueueMessage로 정확히
// 전달하는지, start/stop/awaitTermination이 idempotent하고 실제로 스레드 종료를 기다리는지
// 확인한다.
class SqsAnalysisJobPollerTest {

    private static final String QUEUE_URL = "http://localhost/queue/test";
    private static final int WAIT_TIME_SECONDS = 20;
    private static final int VISIBILITY_TIMEOUT_SECONDS = 90;

    private SqsAnalysisJobPoller newPoller(SqsClient sqsClient, SqsAnalysisJobHandler handler) {
        return new SqsAnalysisJobPoller(sqsClient, handler, QUEUE_URL, WAIT_TIME_SECONDS, VISIBILITY_TIMEOUT_SECONDS);
    }

    @Test
    void receiveMessage는_설정된_waitTimeSeconds_visibilityTimeout_ApproximateReceiveCount_속성을_요청한다() throws Exception {
        SqsClient sqsClient = mock(SqsClient.class);
        SqsAnalysisJobHandler handler = mock(SqsAnalysisJobHandler.class);
        CountDownLatch called = new CountDownLatch(1);

        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class))).thenAnswer(invocation -> {
            called.countDown();
            return ReceiveMessageResponse.builder().messages(List.of()).build();
        });

        SqsAnalysisJobPoller poller = newPoller(sqsClient, handler);
        poller.start();
        assertThat(called.await(5, TimeUnit.SECONDS)).isTrue();
        poller.stop();
        poller.awaitTermination(Duration.ofSeconds(5));

        ArgumentCaptor<ReceiveMessageRequest> captor = ArgumentCaptor.forClass(ReceiveMessageRequest.class);
        verify(sqsClient, org.mockito.Mockito.atLeastOnce()).receiveMessage(captor.capture());
        ReceiveMessageRequest request = captor.getValue();
        assertThat(request.queueUrl()).isEqualTo(QUEUE_URL);
        assertThat(request.maxNumberOfMessages()).isEqualTo(1);
        assertThat(request.waitTimeSeconds()).isEqualTo(WAIT_TIME_SECONDS);
        assertThat(request.visibilityTimeout()).isEqualTo(VISIBILITY_TIMEOUT_SECONDS);
        assertThat(request.attributeNamesAsStrings()).contains("ApproximateReceiveCount");
    }

    @Test
    void ApproximateReceiveCount_속성이_있으면_ReceivedQueueMessage에_정수로_전달한다() throws Exception {
        SqsClient sqsClient = mock(SqsClient.class);
        SqsAnalysisJobHandler handler = mock(SqsAnalysisJobHandler.class);
        Message message = Message.builder()
                .messageId("msg-1")
                .body("{\"eventVersion\":1,\"analysisId\":1}")
                .receiptHandle("receipt-1")
                .attributesWithStrings(Map.of("ApproximateReceiveCount", "2"))
                .build();
        CountDownLatch handled = new CountDownLatch(1);

        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(ReceiveMessageResponse.builder().messages(List.of(message)).build())
                .thenReturn(ReceiveMessageResponse.builder().messages(List.of()).build());
        ArgumentCaptor<ReceivedQueueMessage> messageCaptor = ArgumentCaptor.forClass(ReceivedQueueMessage.class);
        when(handler.handle(messageCaptor.capture())).thenAnswer(invocation -> {
            handled.countDown();
            return SqsAnalysisJobHandler.Outcome.RETAIN;
        });

        SqsAnalysisJobPoller poller = newPoller(sqsClient, handler);
        poller.start();
        assertThat(handled.await(5, TimeUnit.SECONDS)).isTrue();
        poller.stop();
        poller.awaitTermination(Duration.ofSeconds(5));

        assertThat(messageCaptor.getValue().approximateReceiveCount()).isEqualTo(2);
    }

    @Test
    void ApproximateReceiveCount_속성이_없거나_파싱에_실패하면_null을_전달한다() throws Exception {
        SqsClient sqsClient = mock(SqsClient.class);
        SqsAnalysisJobHandler handler = mock(SqsAnalysisJobHandler.class);
        Message messageWithoutAttribute = Message.builder()
                .messageId("msg-1")
                .body("{\"eventVersion\":1,\"analysisId\":1}")
                .receiptHandle("receipt-1")
                .build();
        CountDownLatch handled = new CountDownLatch(1);

        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(ReceiveMessageResponse.builder().messages(List.of(messageWithoutAttribute)).build())
                .thenReturn(ReceiveMessageResponse.builder().messages(List.of()).build());
        ArgumentCaptor<ReceivedQueueMessage> messageCaptor = ArgumentCaptor.forClass(ReceivedQueueMessage.class);
        when(handler.handle(messageCaptor.capture())).thenAnswer(invocation -> {
            handled.countDown();
            return SqsAnalysisJobHandler.Outcome.RETAIN;
        });

        SqsAnalysisJobPoller poller = newPoller(sqsClient, handler);
        poller.start();
        assertThat(handled.await(5, TimeUnit.SECONDS)).isTrue();
        poller.stop();
        poller.awaitTermination(Duration.ofSeconds(5));

        assertThat(messageCaptor.getValue().approximateReceiveCount()).isNull();
    }

    @Test
    void DELETE면_receiptHandle로_deleteMessage를_호출한다() throws Exception {
        SqsClient sqsClient = mock(SqsClient.class);
        SqsAnalysisJobHandler handler = mock(SqsAnalysisJobHandler.class);
        Message message = Message.builder()
                .messageId("msg-1")
                .body("{\"eventVersion\":1,\"analysisId\":1}")
                .receiptHandle("receipt-1")
                .build();
        CountDownLatch handled = new CountDownLatch(1);

        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(ReceiveMessageResponse.builder().messages(List.of(message)).build())
                .thenReturn(ReceiveMessageResponse.builder().messages(List.of()).build());
        when(handler.handle(any(ReceivedQueueMessage.class))).thenAnswer(invocation -> {
            handled.countDown();
            return SqsAnalysisJobHandler.Outcome.DELETE;
        });

        SqsAnalysisJobPoller poller = newPoller(sqsClient, handler);
        poller.start();
        assertThat(handled.await(5, TimeUnit.SECONDS)).isTrue();
        poller.stop();
        assertThat(poller.awaitTermination(Duration.ofSeconds(5))).isTrue();

        verify(sqsClient).deleteMessage(DeleteMessageRequest.builder()
                .queueUrl(QUEUE_URL)
                .receiptHandle("receipt-1")
                .build());
    }

    @Test
    void RETAIN이면_deleteMessage를_호출하지_않는다() throws Exception {
        SqsClient sqsClient = mock(SqsClient.class);
        SqsAnalysisJobHandler handler = mock(SqsAnalysisJobHandler.class);
        Message message = Message.builder()
                .messageId("msg-1")
                .body("{\"eventVersion\":1,\"analysisId\":1}")
                .receiptHandle("receipt-1")
                .build();
        CountDownLatch handled = new CountDownLatch(1);

        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(ReceiveMessageResponse.builder().messages(List.of(message)).build())
                .thenReturn(ReceiveMessageResponse.builder().messages(List.of()).build());
        when(handler.handle(any(ReceivedQueueMessage.class))).thenAnswer(invocation -> {
            handled.countDown();
            return SqsAnalysisJobHandler.Outcome.RETAIN;
        });

        SqsAnalysisJobPoller poller = newPoller(sqsClient, handler);
        poller.start();
        assertThat(handled.await(5, TimeUnit.SECONDS)).isTrue();
        poller.stop();
        poller.awaitTermination(Duration.ofSeconds(5));

        verify(sqsClient, org.mockito.Mockito.never()).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    void stop_신호가_receiveMessage_대기_중에_오면_반환된_메시지를_처리하지_않는다() throws Exception {
        SqsClient sqsClient = mock(SqsClient.class);
        SqsAnalysisJobHandler handler = mock(SqsAnalysisJobHandler.class);
        Message message = Message.builder()
                .messageId("msg-1")
                .body("{\"eventVersion\":1,\"analysisId\":1}")
                .receiptHandle("receipt-1")
                .build();

        CountDownLatch receiveCalled = new CountDownLatch(1);
        CountDownLatch releaseReceive = new CountDownLatch(1);

        // receiveMessage가 아직 반환되지 않은(long polling 대기 중인) 상태를 흉내낸다 -
        // stop()이 그 대기 중에 오고, 그 뒤에야 메시지가 포함된 응답이 돌아온다.
        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class))).thenAnswer(invocation -> {
            receiveCalled.countDown();
            assertThat(releaseReceive.await(5, TimeUnit.SECONDS)).isTrue();
            return ReceiveMessageResponse.builder().messages(List.of(message)).build();
        });

        SqsAnalysisJobPoller poller = newPoller(sqsClient, handler);
        poller.start();
        assertThat(receiveCalled.await(5, TimeUnit.SECONDS)).isTrue();
        poller.stop(); // receiveMessage가 아직 반환되기 전에 종료 신호
        releaseReceive.countDown(); // 이제서야 메시지가 포함된 응답이 돌아온다

        assertThat(poller.awaitTermination(Duration.ofSeconds(5))).isTrue();

        verify(handler, never()).handle(any(ReceivedQueueMessage.class));
        verify(sqsClient, never()).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    void start_stop_awaitTermination은_반복_호출해도_안전하다() throws Exception {
        SqsClient sqsClient = mock(SqsClient.class);
        SqsAnalysisJobHandler handler = mock(SqsAnalysisJobHandler.class);
        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(ReceiveMessageResponse.builder().messages(List.of()).build());

        SqsAnalysisJobPoller poller = newPoller(sqsClient, handler);

        poller.start();
        poller.start(); // 중복 호출해도 예외 없음(같은 스레드 유지)

        poller.stop();
        poller.stop(); // 중복 호출해도 예외 없음

        assertThat(poller.awaitTermination(Duration.ofSeconds(5))).isTrue();
        assertThat(poller.awaitTermination(Duration.ofSeconds(1))).isTrue(); // 이미 종료된 뒤에도 즉시 true
    }

    @Test
    void 시작하지_않은_poller의_awaitTermination은_즉시_true를_반환한다() {
        SqsAnalysisJobPoller poller = newPoller(mock(SqsClient.class), mock(SqsAnalysisJobHandler.class));

        assertThat(poller.awaitTermination(Duration.ofMillis(100))).isTrue();
    }
}
