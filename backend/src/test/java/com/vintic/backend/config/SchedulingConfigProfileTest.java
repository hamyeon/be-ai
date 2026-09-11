package com.vintic.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

// SchedulingConfig(@EnableScheduling)가 experiment-worker 프로필에서만 꺼지고, 그 외
// (프로필 없음 = local/dev/prod와 동일한 기본 상태 포함)에서는 기존과 동일하게 켜지는지 확인한다.
// @EnableScheduling이 실제로 등록하는 internalScheduledAnnotationProcessor 빈의 존재 여부로
// "Scheduler가 기동하는지"를 판별한다 - 개별 @Scheduled 빈 자체는 프로필과 무관하게 항상
// 등록되므로(트리거만 내부에서 no-op) 이 내부 빈이 더 정확한 신호다.
class SchedulingConfigProfileTest {

    private static final String SCHEDULED_PROCESSOR_BEAN =
            "org.springframework.context.annotation.internalScheduledAnnotationProcessor";

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(SchedulingConfig.class);

    @Test
    void 프로필이_없으면_기존과_동일하게_스케줄러가_켜진다() {
        contextRunner.run(context ->
                assertThat(context).hasBean(SCHEDULED_PROCESSOR_BEAN));
    }

    @Test
    void local_프로필에서도_스케줄러가_켜진다() {
        contextRunner
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("local"))
                .run(context -> assertThat(context).hasBean(SCHEDULED_PROCESSOR_BEAN));
    }

    @Test
    void experiment_worker_프로필에서는_스케줄러가_꺼진다() {
        contextRunner
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("experiment-worker"))
                .run(context -> assertThat(context).doesNotHaveBean(SCHEDULED_PROCESSOR_BEAN));
    }

    @Test
    void experiment_api_프로필에서는_스케줄러가_영향받지_않는다() {
        contextRunner
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("experiment-api"))
                .run(context -> assertThat(context).hasBean(SCHEDULED_PROCESSOR_BEAN));
    }

    @Test
    void redis_baseline_test_프로필에서는_스케줄러가_꺼진다() {
        contextRunner
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("redis-baseline-test"))
                .run(context -> assertThat(context).doesNotHaveBean(SCHEDULED_PROCESSOR_BEAN));
    }
}
