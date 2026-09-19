package com.vintic.backend.config;

import com.vintic.backend.BackendApplication;
import com.vintic.backend.analyze.job.SyncAnalysisController;
import com.vintic.backend.analyze.job.metrics.AnalysisJobMetrics;
import com.vintic.backend.analyze.job.worker.SqsAnalysisJobHandler;
import com.vintic.backend.analyze.queue.RedisStreamConsumerConfig;
import io.micrometer.cloudwatch2.CloudWatchMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

// experiment-api/experiment-worker 프로필이 실제로 Controller(웹 계층)와 기존 Redis Streams
// Consumer를 의도한 대로 켜고 끄는지, 전체 애플리케이션을 직접 기동해 확인한다.
//
// @SpringBootTest(webEnvironment=...)를 쓰지 않는다 - 그 애너테이션은 webEnvironment 값에 따라
// spring.main.web-application-type을 테스트 하네스가 강제로 덮어써서, experiment-worker.yml이
// 설정한 web-application-type=none이 실제로 적용됐는지를 이 테스트가 구분할 수 없게 만든다.
// 대신 SpringApplicationBuilder로 실제 배포와 동일한 방식으로 기동하고 property 해석 결과를
// 그대로 관찰한다.
//
// datasource를 지정하지 않는 프로필(experiment-api/experiment-worker 단독)이므로 Spring Boot가
// 내장 H2로 자동 대체한다 - 실제 배포에서는 이 프로필들이 항상 local/dev/prod와 함께 켜져
// 그 프로필의 DB 설정을 쓰지만, 이 테스트는 프로필 자체의 격리 효과만 보는 것이므로 무관하다.
// analysis.job.queue.type은 InMemoryQueuePublisher로 명시해 "설정 없으면 기동 실패"라는
// 기존 fail-fast 규칙에 걸리지 않게 한다.
//
// Day5부터 이 두 프로필은 spring.flyway.enabled=true + ddl-auto=validate를 쓴다(V1/V2
// migration으로 실제 MySQL과 함께 켜졌을 때만 의미 있음). 여기서는 H2로 대체되는 격리
// 테스트라 MySQL 전용 migration SQL이 그대로 안 맞으므로 두 값을 다시 꺼서 이 테스트의
// 원래 목적(프로필별 빈 등록 여부)만 검증한다.
class ExperimentProfileContextTest {

    @Test
    void experiment_api는_웹_계층이_뜨고_기존_Redis_Consumer는_꺼진다() {
        ConfigurableApplicationContext context = new SpringApplicationBuilder(BackendApplication.class)
                .profiles("experiment-api")
                .run("--analysis.job.queue.type=in-memory", "--server.port=0",
                        "--spring.flyway.enabled=false", "--spring.jpa.hibernate.ddl-auto=update");

        try {
            assertThat(context).isInstanceOf(WebServerApplicationContext.class);
            assertThat(context.getBeansOfType(RedisStreamConsumerConfig.class)).isEmpty();
            assertThat(context.getBeansOfType(SyncAnalysisController.class)).hasSize(1);
            // Day10: API 프로세스는 Worker 전용 metric(SqsAnalysisJobHandler/AnalysisJobMetrics)을
            // 만들지 않는다 - API에서 항상 0인 Worker 시계열이 CloudWatch에 쌓이는 것을 막는다.
            assertThat(context.getBeansOfType(SqsAnalysisJobHandler.class)).isEmpty();
            assertThat(context.getBeansOfType(AnalysisJobMetrics.class)).isEmpty();
        } finally {
            context.close();
        }
    }

    @Test
    void experiment_api는_CLOUDWATCH_METRICS_ENABLED_기본값이_false라_registry가_등록되지_않는다() {
        ConfigurableApplicationContext context = new SpringApplicationBuilder(BackendApplication.class)
                .profiles("experiment-api")
                .run("--analysis.job.queue.type=in-memory", "--server.port=0",
                        "--spring.flyway.enabled=false", "--spring.jpa.hibernate.ddl-auto=update");

        try {
            assertThat(context.getBeansOfType(CloudWatchMeterRegistry.class)).isEmpty();
        } finally {
            context.close();
        }
    }

    @Test
    void experiment_api_단독으로는_mock_auth가_등록되지_않는다() {
        // experiment.auth.mock-enabled를 아예 지정하지 않는다 - profile 하나만으로는 이중
        // 게이트를 통과하지 못한다는 것이 이 테스트의 관심사다(ExperimentMockAuthWebConfig 참고).
        ConfigurableApplicationContext context = new SpringApplicationBuilder(BackendApplication.class)
                .profiles("experiment-api")
                .run("--analysis.job.queue.type=in-memory", "--server.port=0",
                        "--spring.flyway.enabled=false", "--spring.jpa.hibernate.ddl-auto=update");

        try {
            assertThat(context.getBeansOfType(ExperimentMockAuthWebConfig.class)).isEmpty();
        } finally {
            context.close();
        }
    }

    @Test
    void experiment_api_mock_enabled_false면_mock_auth가_등록되지_않는다() {
        ConfigurableApplicationContext context = new SpringApplicationBuilder(BackendApplication.class)
                .profiles("experiment-api")
                .run("--analysis.job.queue.type=in-memory", "--server.port=0",
                        "--spring.flyway.enabled=false", "--spring.jpa.hibernate.ddl-auto=update",
                        "--experiment.auth.mock-enabled=false");

        try {
            assertThat(context.getBeansOfType(ExperimentMockAuthWebConfig.class)).isEmpty();
        } finally {
            context.close();
        }
    }

    @Test
    void experiment_api_profile과_mock_enabled_true가_모두_있어야_mock_auth가_등록된다() {
        ConfigurableApplicationContext context = new SpringApplicationBuilder(BackendApplication.class)
                .profiles("experiment-api")
                .run("--analysis.job.queue.type=in-memory", "--server.port=0",
                        "--spring.flyway.enabled=false", "--spring.jpa.hibernate.ddl-auto=update",
                        "--experiment.auth.mock-enabled=true");

        try {
            assertThat(context.getBeansOfType(ExperimentMockAuthWebConfig.class)).hasSize(1);
        } finally {
            context.close();
        }
    }

    @Test
    void experiment_worker는_mock_enabled_true여도_mock_auth가_등록되지_않는다() {
        // ExperimentMockAuthWebConfig는 @Profile("experiment-api") 전용이다 - worker는
        // 웹 계층 자체가 없으므로(web-application-type=none) 애초에 무관하지만, mock-enabled
        // 값과 무관하게 절대 등록되지 않는다는 것을 명시적으로 확인한다.
        ConfigurableApplicationContext context = new SpringApplicationBuilder(BackendApplication.class)
                .profiles("experiment-worker")
                .run("--analysis.job.queue.type=sqs",
                        "--analysis.job.queue.sqs.queue-url=http://localhost:1/000000000000/unused-queue",
                        "--analysis.job.queue.sqs.region=ap-northeast-2",
                        "--analysis.job.queue.sqs.endpoint-override=http://localhost:1",
                        "--spring.flyway.enabled=false", "--spring.jpa.hibernate.ddl-auto=update",
                        "--experiment.auth.mock-enabled=true");

        try {
            assertThat(context.getBeansOfType(ExperimentMockAuthWebConfig.class)).isEmpty();
        } finally {
            context.close();
        }
    }

    @Test
    void experiment_worker는_웹_계층이_뜨지_않고_기존_Redis_Consumer도_꺼진다() {
        // WorkerQueueTypeGuard가 analysis.job.queue.type=sqs를 요구하므로(그 값이 아니면
        // 기동 자체가 실패한다) in-memory 대신 sqs + 즉시 실패하는 로컬 endpoint를 쓴다 - 이
        // 테스트의 관심사는 실제 polling 성공이 아니라 "웹 계층/기존 Redis Consumer가 꺼지는가"
        // 뿐이다(실제 long polling 생존/graceful stop 검증은
        // SqsAnalysisJobPollerLifecycleLocalStackIT가 실제 LocalStack으로 따로 한다).
        ConfigurableApplicationContext context = new SpringApplicationBuilder(BackendApplication.class)
                .profiles("experiment-worker")
                .run("--analysis.job.queue.type=sqs",
                        "--analysis.job.queue.sqs.queue-url=http://localhost:1/000000000000/unused-queue",
                        "--analysis.job.queue.sqs.region=ap-northeast-2",
                        "--analysis.job.queue.sqs.endpoint-override=http://localhost:1",
                        "--spring.flyway.enabled=false", "--spring.jpa.hibernate.ddl-auto=update");

        try {
            assertThat(context).isNotInstanceOf(WebServerApplicationContext.class);
            assertThat(context.getBeansOfType(RedisStreamConsumerConfig.class)).isEmpty();
            assertThat(context.containsBean(
                    "org.springframework.context.annotation.internalScheduledAnnotationProcessor"))
                    .isFalse();
            assertThat(context.getBeansOfType(SyncAnalysisController.class)).isEmpty();
            // Day10: experiment-worker에서는 반대로 이 두 Bean이 정상적으로 등록되어야 한다.
            assertThat(context.getBeansOfType(SqsAnalysisJobHandler.class)).hasSize(1);
            assertThat(context.getBeansOfType(AnalysisJobMetrics.class)).hasSize(1);
        } finally {
            context.close();
        }
    }

    @Test
    void experiment_worker는_CLOUDWATCH_METRICS_ENABLED_기본값이_false라_registry가_등록되지_않는다() {
        ConfigurableApplicationContext context = new SpringApplicationBuilder(BackendApplication.class)
                .profiles("experiment-worker")
                .run("--analysis.job.queue.type=sqs",
                        "--analysis.job.queue.sqs.queue-url=http://localhost:1/000000000000/unused-queue",
                        "--analysis.job.queue.sqs.region=ap-northeast-2",
                        "--analysis.job.queue.sqs.endpoint-override=http://localhost:1",
                        "--spring.flyway.enabled=false", "--spring.jpa.hibernate.ddl-auto=update");

        try {
            assertThat(context.getBeansOfType(CloudWatchMeterRegistry.class)).isEmpty();
        } finally {
            context.close();
        }
    }

    @Test
    void experiment_worker는_CLOUDWATCH_METRICS_ENABLED가_true면_registry를_등록하고_공통_dimension과_allowlist를_적용한다() {
        ConfigurableApplicationContext context = new SpringApplicationBuilder(BackendApplication.class)
                .profiles("experiment-worker")
                .run("--analysis.job.queue.type=sqs",
                        "--analysis.job.queue.sqs.queue-url=http://localhost:1/000000000000/unused-queue",
                        "--analysis.job.queue.sqs.region=ap-northeast-2",
                        "--analysis.job.queue.sqs.endpoint-override=http://localhost:1",
                        "--spring.flyway.enabled=false", "--spring.jpa.hibernate.ddl-auto=update",
                        "--management.cloudwatch.metrics.export.enabled=true");

        try {
            assertThat(context.getBeansOfType(CloudWatchMeterRegistry.class)).hasSize(1);
            CloudWatchMeterRegistry registry = context.getBean(CloudWatchMeterRegistry.class);

            registry.counter("autique.analysis.worker.events", "event", "retry").increment();
            registry.counter("not.in.allowlist").increment();

            assertThat(registry.find("autique.analysis.worker.events").counter()).isNotNull();
            assertThat(registry.find("not.in.allowlist").counter()).isNull();
            assertThat(registry.find("autique.analysis.worker.events").counter().getId().getTags())
                    .contains(io.micrometer.core.instrument.Tag.of("environment", "experiment"))
                    .contains(io.micrometer.core.instrument.Tag.of("service", "worker"));
        } finally {
            context.close();
        }
    }
}
