package com.vintic.backend.analyze.queue;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.stream.StreamMessageListenerContainer.ConsumerStreamReadRequest;
import org.springframework.data.redis.stream.StreamMessageListenerContainer.StreamReadRequest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class StreamPollErrorHandlerTest {

    // 테스트 중에 시간을 앞으로 돌릴 수 있는 시계
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-17T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }

    @Test
    void 구독은_어떤_예외에도_취소되지_않고_ACK는_직접_한다() {
        // #106: Spring Data Redis 기본값은 예외 한 번에 구독을 영구히 취소한다.
        StreamReadRequest<String> request = RedisStreamConsumerConfig.readRequest(
                "stream", "group", "worker-a-0", new StreamPollErrorHandler(() -> { }));

        assertThat(request.getCancelSubscriptionOnError().test(new RuntimeException("Connection refused"))).isFalse();
        assertThat(request).isInstanceOf(ConsumerStreamReadRequest.class);
        ConsumerStreamReadRequest<String> consumerRequest = (ConsumerStreamReadRequest<String>) request;
        assertThat(consumerRequest.isAutoAcknowledge()).isFalse();
        assertThat(consumerRequest.getConsumer()).isEqualTo(Consumer.from("group", "worker-a-0"));
    }

    @Test
    void 동시_처리_수만큼_서로_다른_Consumer_이름을_만든다() {
        assertThat(RedisStreamConsumerConfig.consumerNames("worker", "abc", 3))
                .containsExactly("worker-abc-0", "worker-abc-1", "worker-abc-2");
        // 잘못 설정해도 최소 하나는 띄운다
        assertThat(RedisStreamConsumerConfig.consumerNames("worker", "abc", 0)).containsExactly("worker-abc-0");
    }

    @Test
    void 연속_실패하면_대기가_1초부터_두_배씩_늘고_30초에서_멈춘다() {
        assertThat(StreamPollErrorHandler.backoffFor(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(StreamPollErrorHandler.backoffFor(2)).isEqualTo(Duration.ofSeconds(2));
        assertThat(StreamPollErrorHandler.backoffFor(5)).isEqualTo(Duration.ofSeconds(16));
        assertThat(StreamPollErrorHandler.backoffFor(6)).isEqualTo(Duration.ofSeconds(30));
        assertThat(StreamPollErrorHandler.backoffFor(100)).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void 실패마다_폴링_스레드를_재우고_한동안_조용하면_대기를_처음부터_다시_센다() {
        MutableClock clock = new MutableClock();
        List<Long> sleeps = new ArrayList<>();
        StreamPollErrorHandler handler = new StreamPollErrorHandler(() -> { }, clock, sleeps::add);

        handler.handleError(new RuntimeException("Connection refused"));
        handler.handleError(new RuntimeException("Connection refused"));
        handler.handleError(new RuntimeException("Connection refused"));
        clock.advance(Duration.ofMinutes(5));
        handler.handleError(new RuntimeException("Connection refused"));

        assertThat(sleeps).containsExactly(1_000L, 2_000L, 4_000L, 1_000L);
    }

    @Test
    void Consumer_Group이_사라지면_다시_만든다() {
        AtomicInteger recreated = new AtomicInteger();
        StreamPollErrorHandler handler = new StreamPollErrorHandler(
                recreated::incrementAndGet, new MutableClock(), millis -> { });

        handler.handleError(new RedisSystemException("Error in execution",
                new RuntimeException("NOGROUP No such key 'ai:analysis:requests' or consumer group 'ai-analysis-workers'")));
        handler.handleError(new RuntimeException("Connection refused"));

        assertThat(recreated.get()).isEqualTo(1);
    }

    @Test
    void 기다리는_중_인터럽트되면_인터럽트_표시를_되살린다() {
        StreamPollErrorHandler handler = new StreamPollErrorHandler(() -> { }, new MutableClock(), millis -> {
            throw new InterruptedException();
        });

        handler.handleError(new RuntimeException("Connection refused"));

        // 폴링 루프가 종료를 알아챌 수 있어야 한다. 확인 후 다음 테스트에 새지 않게 지운다.
        assertThat(Thread.interrupted()).isTrue();
    }
}
