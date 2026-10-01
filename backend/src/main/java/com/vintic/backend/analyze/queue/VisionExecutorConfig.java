package com.vintic.backend.analyze.queue;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

// AnalysisTaskConsumer가 visionAnalysisService.analyze()를 이 executor에서 돌리고
// Future.get(overallTimeoutMs)로 감싸 "더 이상 기다리지 않고 실패 처리로 넘어가는" 상한을 둔다.
// 다만 Future.get()을 포기해도 이미 제출된 작업 자체가 즉시 멈추는 건 아니다(AnalysisTaskConsumer
// 주석 참고) - 그래서 풀/큐를 무한정 늘리지 않는다: 큐가 가득 차면(좀비 시도가 쌓인 상황) 새
// submit()이 RejectedExecutionException으로 즉시 실패해, 뒤에 줄 서서 무한정 기다리는 대신
// 빠르게 실패 처리(VISION_FAILED)로 넘어갈 수 있게 한다.
@Configuration
@RequiredArgsConstructor
public class VisionExecutorConfig {

    private final AnalysisVisionProcessingProperties visionProperties;

    @Bean(destroyMethod = "shutdown")
    public ExecutorService visionAnalysisExecutor() {
        AtomicLong threadCount = new AtomicLong();
        return new ThreadPoolExecutor(
                visionProperties.getExecutorPoolSize(),
                visionProperties.getExecutorPoolSize(),
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(visionProperties.getExecutorQueueCapacity()),
                runnable -> {
                    Thread thread = new Thread(runnable, "vision-analysis-" + threadCount.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }
                // RejectedExecutionHandler는 기본값(AbortPolicy)을 그대로 쓴다 - 큐가 가득 차면
                // submit()이 예외를 던져야 호출부(AnalysisTaskConsumer)가 즉시 실패로 처리할 수 있다.
        );
    }
}
