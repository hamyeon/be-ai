package com.vintic.backend.analyze.queue;

import lombok.extern.slf4j.Slf4j;
import org.springframework.util.ErrorHandler;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

// Stream 구독의 폴링 루프에서 난 예외를 처리한다(#106).
//
// 구독을 끊지 않기로 했으므로(cancelOnError = false) 그 대가를 여기서 치른다.
// - Redis가 내려가 있으면 읽기가 즉시 실패해 루프가 쉬지 않고 돈다. 그래서 폴링 스레드를 잠깐 재운다.
//   이 핸들러는 폴링 스레드 안에서 불리므로 여기서 기다리는 만큼 다음 읽기가 늦어진다.
// - 연속 실패하면 대기를 늘려(1초 -> 최대 30초) 로그와 재접속 시도가 폭주하지 않게 한다.
// - 스트림 키가 지워지면(FLUSHALL 등) 그룹도 같이 사라져 NOGROUP이 영원히 반복된다. 그룹을 다시 만든다.
@Slf4j
class StreamPollErrorHandler implements ErrorHandler {

    static final Duration INITIAL_BACKOFF = Duration.ofSeconds(1);
    static final Duration MAX_BACKOFF = Duration.ofSeconds(30);
    // 마지막 실패 후 이만큼 조용했으면 연속 실패로 보지 않는다.
    static final Duration STREAK_RESET = Duration.ofMinutes(1);

    private final Runnable recreateGroup;
    private final Clock clock;
    private final Sleeper sleeper;

    private int consecutiveFailures;
    private Instant lastFailureAt;

    StreamPollErrorHandler(Runnable recreateGroup) {
        this(recreateGroup, Clock.systemUTC(), Thread::sleep);
    }

    StreamPollErrorHandler(Runnable recreateGroup, Clock clock, Sleeper sleeper) {
        this.recreateGroup = recreateGroup;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    @Override
    public synchronized void handleError(Throwable error) {
        Instant now = clock.instant();
        if (lastFailureAt == null || Duration.between(lastFailureAt, now).compareTo(STREAK_RESET) > 0) {
            consecutiveFailures = 0;
        }
        consecutiveFailures++;
        lastFailureAt = now;

        Duration backoff = backoffFor(consecutiveFailures);
        // 같은 장애로 수백 줄이 찍히지 않게 첫 실패만 스택을 남긴다.
        if (consecutiveFailures == 1) {
            log.error("Redis Stream 폴링 실패 - 구독은 유지하고 {}ms 뒤 다시 읽습니다.", backoff.toMillis(), error);
        } else {
            log.warn("Redis Stream 폴링 연속 실패 {}회 - {}ms 뒤 다시 읽습니다. 원인: {}",
                    consecutiveFailures, backoff.toMillis(), error.getMessage());
        }

        if (isMissingGroup(error)) {
            log.warn("Consumer Group이 없습니다(스트림 키가 삭제됐을 수 있음). 다시 만듭니다.");
            recreateGroup.run();
        }

        try {
            sleeper.sleep(backoff.toMillis());
        } catch (InterruptedException e) {
            // 컨테이너 종료 중이다. 인터럽트 표시를 되살려 폴링 루프가 멈출 수 있게 한다.
            Thread.currentThread().interrupt();
        }
    }

    static Duration backoffFor(int consecutiveFailures) {
        int exponent = Math.min(Math.max(consecutiveFailures - 1, 0), 10);
        Duration backoff = INITIAL_BACKOFF.multipliedBy(1L << exponent);
        return backoff.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : backoff;
    }

    private static boolean isMissingGroup(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && cause.getMessage().contains("NOGROUP")) {
                return true;
            }
        }
        return false;
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }
}
