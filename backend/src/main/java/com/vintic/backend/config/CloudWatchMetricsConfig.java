package com.vintic.backend.config;

import io.micrometer.cloudwatch2.CloudWatchConfig;
import io.micrometer.cloudwatch2.CloudWatchMeterRegistry;
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient;

import java.util.Map;
import java.util.Set;

// Day10 관측성: experiment-api/experiment-worker 한정 제한된 Micrometer meter를 CloudWatch
// namespace Autique/Experiment로 내보낸다. Spring Boot 3.x에는 CloudWatch metrics export
// 자동 구성이 없다(management.cloudwatch.metrics.export.*는 Spring Boot가 아는 표준 키가
// 아니라 이 클래스가 직접 바인딩하는 임의의 키) - 그래서 이 registry는 이 @Configuration을
// 거치지 않고는 절대 등록되지 않는다.
//
// management.cloudwatch.metrics.export.enabled(=CLOUDWATCH_METRICS_ENABLED)가 true가 아니면
// 클래스 전체가 조건에 걸려 CloudWatchAsyncClient/CloudWatchMeterRegistry 어느 것도 만들지
// 않는다 - 기본값은 false다.
//
// 비용 통제를 위해 이 experiment가 감시하려는 8개 meter만 CloudWatch로 내보내고 나머지는
// MeterFilter.denyUnless로 이 registry에서만 걸러낸다 - AiCallMetrics 등 기존 meter는
// (다른 MeterRegistry에는 이 filter가 적용되지 않으므로) 그대로 기록된다.
@Configuration
@Profile({"experiment-api", "experiment-worker"})
@ConditionalOnProperty(prefix = "management.cloudwatch.metrics.export", name = "enabled", havingValue = "true")
public class CloudWatchMetricsConfig {

    private static final Set<String> ALLOWED_METERS = Set.of(
            "autique.analysis.processor.calls",
            "autique.analysis.processor.failures",
            "autique.analysis.processor.latency",
            "autique.analysis.queue.wait",
            "autique.analysis.worker.events",
            "hikaricp.connections.active",
            "hikaricp.connections.pending",
            "hikaricp.connections.max"
    );

    // region은 기존 cloud.aws.region.static을 재사용한다 - CloudWatch 전용 region 키를
    // 새로 만들지 않는다. 자격증명은 DefaultCredentialsProvider(EC2 IAM Role 등 기본 체인)만
    // 쓴다 - S3Presigner/SqsWorkerClientConfig와 동일한 방식이다.
    @Bean
    public CloudWatchAsyncClient cloudWatchAsyncClient(@Value("${cloud.aws.region.static}") String region) {
        return CloudWatchAsyncClient.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    // step은 @Value(Duration)이 아니라 문자열로 받아 DurationStyle로 직접 파싱한다 - Spring Boot의
    // 암묵적 String->Duration 변환(ApplicationConversionService)은 SpringApplication.run()으로
    // 기동한 컨텍스트에만 등록되고, 가벼운 ApplicationContextRunner 기반 단위 테스트에는 없다.
    @Bean
    public CloudWatchConfig cloudWatchExportConfig(
            @Value("${management.cloudwatch.metrics.export.namespace}") String namespace,
            @Value("${management.cloudwatch.metrics.export.step}") String step) {
        Map<String, String> properties = Map.of(
                "cloudwatch.namespace", namespace,
                "cloudwatch.step", DurationStyle.detectAndParse(step).toString());
        return properties::get;
    }

    // environment/service는 Environment.acceptsProfiles로 추론하지 않고 각 experiment profile의
    // yml(application-experiment-api.yml/application-experiment-worker.yml)이 명시한 값을
    // 그대로 주입받는다 - 두 profile이 실수로 동시에 켜졌을 때 service 값이 애매해지는 것을
    // 막기 위함이다.
    //
    // CloudWatchMeterRegistry 생성자(StepMeterRegistry)가 이미 주기적 publish를 스케줄링하므로
    // registry.start()를 별도로 호출하지 않는다.
    @Bean
    public CloudWatchMeterRegistry cloudWatchMeterRegistry(
            CloudWatchConfig cloudWatchExportConfig,
            CloudWatchAsyncClient cloudWatchAsyncClient,
            @Value("${management.cloudwatch.metrics.export.environment}") String environment,
            @Value("${management.cloudwatch.metrics.export.service}") String service) {
        CloudWatchMeterRegistry registry =
                new CloudWatchMeterRegistry(cloudWatchExportConfig, Clock.SYSTEM, cloudWatchAsyncClient);
        registry.config()
                .commonTags("environment", environment, "service", service)
                .meterFilter(MeterFilter.denyUnless(id -> ALLOWED_METERS.contains(id.getName())));
        return registry;
    }
}
