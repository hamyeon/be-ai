package com.vintic.backend.config;

import io.micrometer.cloudwatch2.CloudWatchMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient;

import static org.assertj.core.api.Assertions.assertThat;

// CloudWatchMetricsConfig가 experiment-api/experiment-worker 프로필 + management.cloudwatch.
// metrics.export.enabled=true일 때만 CloudWatchMeterRegistry(및 그 의존 Bean)를 등록하는지
// 확인한다(SqsWorkerClientConfigProfileTest와 동일한 ApplicationContextRunner 방식) - 실제
// AWS 호출 없이 Bean 등록 여부만 본다.
class CloudWatchMetricsConfigProfileTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(CloudWatchMetricsConfig.class)
            .withPropertyValues(
                    "cloud.aws.region.static=ap-northeast-2",
                    "management.cloudwatch.metrics.export.namespace=Autique/Experiment",
                    "management.cloudwatch.metrics.export.step=60s",
                    "management.cloudwatch.metrics.export.environment=experiment");

    @Test
    void enabled를_설정하지_않으면_기본값_false라_등록되지_않는다() {
        contextRunner
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("experiment-api"))
                .withPropertyValues("management.cloudwatch.metrics.export.service=api")
                .run(context -> assertThat(context).doesNotHaveBean(CloudWatchMeterRegistry.class));
    }

    @Test
    void profile이_experiment_api_worker가_아니면_enabled여도_등록되지_않는다() {
        contextRunner
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("local"))
                .withPropertyValues(
                        "management.cloudwatch.metrics.export.enabled=true",
                        "management.cloudwatch.metrics.export.service=api")
                .run(context -> assertThat(context).doesNotHaveBean(CloudWatchMeterRegistry.class));
    }

    @Test
    void experiment_api_profile과_enabled_true가_모두_있으면_service_api로_등록된다() {
        contextRunner
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("experiment-api"))
                .withPropertyValues(
                        "management.cloudwatch.metrics.export.enabled=true",
                        "management.cloudwatch.metrics.export.service=api")
                .run(context -> {
                    assertThat(context).hasSingleBean(CloudWatchMeterRegistry.class);
                    assertThat(context).hasSingleBean(CloudWatchAsyncClient.class);

                    CloudWatchMeterRegistry registry = context.getBean(CloudWatchMeterRegistry.class);
                    registry.counter("autique.analysis.processor.failures").increment();
                    registry.counter("not.in.allowlist").increment();

                    assertThat(registry.find("autique.analysis.processor.failures").counter()).isNotNull();
                    assertThat(registry.find("not.in.allowlist").counter()).isNull();
                    assertThat(registry.find("autique.analysis.processor.failures").counter().getId().getTags())
                            .contains(
                                    io.micrometer.core.instrument.Tag.of("environment", "experiment"),
                                    io.micrometer.core.instrument.Tag.of("service", "api"));
                });
    }

    @Test
    void experiment_worker_profile과_enabled_true가_모두_있으면_service_worker로_등록된다() {
        contextRunner
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("experiment-worker"))
                .withPropertyValues(
                        "management.cloudwatch.metrics.export.enabled=true",
                        "management.cloudwatch.metrics.export.service=worker")
                .run(context -> {
                    assertThat(context).hasSingleBean(CloudWatchMeterRegistry.class);
                    CloudWatchMeterRegistry registry = context.getBean(CloudWatchMeterRegistry.class);
                    registry.counter("hikaricp.connections.active").increment();

                    assertThat(registry.find("hikaricp.connections.active").counter().getId().getTags())
                            .contains(io.micrometer.core.instrument.Tag.of("service", "worker"));
                });
    }
}
