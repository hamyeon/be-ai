package com.vintic.backend.analyze.job.worker;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// queueWaitMs() 계산 규칙만 검증한다 - max(0, receivedAt - sentTimestamp), sentTimestamp가
// 없으면(null) 계산 불가로 null, 시계 오차 등으로 음수가 나오면 0으로 clamp.
class ReceivedQueueMessageTest {

    @Test
    void sentTimestamp가_있으면_수신_시각과의_차이를_밀리초로_계산한다() {
        ReceivedQueueMessage message = new ReceivedQueueMessage("msg-1", "body", 1, 1_000L, 1_750L);

        assertThat(message.queueWaitMs()).isEqualTo(750L);
    }

    @Test
    void sentTimestamp가_null이면_queueWaitMs도_null이다() {
        ReceivedQueueMessage message = new ReceivedQueueMessage("msg-1", "body", 1, null, 1_750L);

        assertThat(message.queueWaitMs()).isNull();
    }

    @Test
    void 시계_오차로_음수가_나오면_0으로_clamp한다() {
        ReceivedQueueMessage message = new ReceivedQueueMessage("msg-1", "body", 1, 2_000L, 1_000L);

        assertThat(message.queueWaitMs()).isEqualTo(0L);
    }

    @Test
    void 발행과_수신이_같은_시각이면_0이다() {
        ReceivedQueueMessage message = new ReceivedQueueMessage("msg-1", "body", 1, 1_000L, 1_000L);

        assertThat(message.queueWaitMs()).isEqualTo(0L);
    }
}
