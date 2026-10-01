package com.vintic.backend.ai.vision.agent;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// 단계를 동시에 부를 때 쓰는 스레드 풀(#106). PARALLEL_LABEL_CONDITION이면 2·3단계,
// ALL_PARALLEL이면 1·2·3단계 전부를 여기서 돌린다.
//
// 공용 ForkJoinPool을 쓰지 않는다. 거기 있는 스레드 수는 CPU 코어 수 기준인데 이 작업은 계산이 아니라
// API 응답을 기다리는 일이라, 다른 병렬 작업까지 같이 막힐 수 있다.
@Configuration
@RequiredArgsConstructor
public class VisionStageExecutorConfig {

    public static final String VISION_STAGE_EXECUTOR = "visionStageExecutor";

    private final VisionStageProperties stageProperties;

    // destroyMethod로 종료를 맡긴다. 데몬 스레드로 만들어 종료가 늦어도 JVM이 붙잡히지 않게 한다.
    @Bean(name = VISION_STAGE_EXECUTOR, destroyMethod = "shutdown")
    public ExecutorService visionStageExecutor() {
        return Executors.newFixedThreadPool(Math.max(1, stageProperties.getParallelPoolSize()), runnable -> {
            Thread thread = new Thread(runnable, "vision-stage");
            thread.setDaemon(true);
            return thread;
        });
    }
}
